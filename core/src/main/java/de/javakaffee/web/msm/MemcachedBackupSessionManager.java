/*
 * Copyright 2009 Martin Grotzke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package de.javakaffee.web.msm;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.spy.memcached.MemcachedClient;
import net.spy.memcached.transcoders.SerializingTranscoder;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.NodeAvailabilityCache.CacheLoader;
import de.javakaffee.web.msm.NodeIdResolver.MapBasedResolver;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService;

/**
 * This {@link Manager} stores session in configured memcached nodes after the
 * response is finished (committed).
 * <p>
 * Use this session manager in a Context element, like this <code><pre>
 * &lt;Context path="/foo"&gt;
 *     &lt;Manager className="de.javakaffee.web.msm.MemcachedBackupSessionManager"
 *         memcachedNodes="n1.localhost:11211 n2.localhost:11212" failoverNodes="n2"
 *         requestUriIgnorePattern=".*\.(png|gif|jpg|css|js)$" /&gt;
 * &lt;/Context&gt;
 * </pre></code>
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class MemcachedBackupSessionManager extends ManagerBase implements Lifecycle, SessionBackupService, PropertyChangeListener {

    protected static final String NAME = MemcachedBackupSessionManager.class.getSimpleName();

    private static final String INFO = NAME + "/1.0";

    private static final String NODE_REGEX = "([\\w]+):([^:]+):([\\d]+)";
    private static final Pattern NODE_PATTERN = Pattern.compile( NODE_REGEX );

    private static final String NODES_REGEX = NODE_REGEX + "(?:(?:\\s+|,)" + NODE_REGEX + ")*";
    private static final Pattern NODES_PATTERN = Pattern.compile( NODES_REGEX );

    protected static final String NODE_FAILURE = "node.failure";

    private final Log _log = LogFactory.getLog( MemcachedBackupSessionManager.class );

    private final LifecycleSupport _lifecycle = new LifecycleSupport( this );

    private final SessionIdFormat _sessionIdFormat = new SessionIdFormat();

    // -------------------- configuration properties --------------------

    /**
     * The memcached nodes space separated and with the id prefix, e.g.
     * n1:localhost:11211 n2:localhost:11212
     *
     */
    private String _memcachedNodes;

    /**
     * The ids of memcached failover nodes separated by space, e.g.
     * <code>n1 n2</code>
     *
     */
    private String _failoverNodes;

    /**
     * The pattern used for excluding requests from a session-backup, e.g.
     * <code>.*\.(png|gif|jpg|css|js)$</code>. Is matched against
     * request.getRequestURI.
     */
    private String _requestUriIgnorePattern;

    /**
     * Specifies if the session shall be stored asynchronously in memcached as
     * {@link MemcachedClient#set(String, int, Object)} supports it. If this is
     * false, the timeout set via {@link #setSessionBackupTimeout(int)} is
     * evaluated.
     * <p>
     * Notice: if the session backup is done asynchronously, it is possible that
     * a session cannot be stored in memcached and we don't notice that -
     * therefore the session would not get relocated to another memcached node.
     * </p>
     * <p>
     * By default this property is set to <code>false</code> - the session
     * backup is performed synchronously.
     * </p>
     */
    private boolean _sessionBackupAsync = false;

    /**
     * The timeout in milliseconds after that a session backup is considered as
     * beeing failed.
     * <p>
     * This property is only evaluated if sessions are stored synchronously (set
     * via {@link #setSessionBackupAsync(boolean)}).
     * </p>
     * <p>
     * The default value is <code>100</code> millis.
     * </p>
     */
    private int _sessionBackupTimeout = 100;

    /**
     * The class of the factory for
     * {@link net.spy.memcached.transcoders.Transcoder}s. Default class is
     * {@link JavaSerializationTranscoderFactory}.
     */
    private Class<? extends TranscoderFactory> _transcoderFactoryClass = JavaSerializationTranscoderFactory.class;

    /**
     * Specifies, if iterating over collection elements shall be done on a copy
     * of the collection or on the collection itself.
     * <p>
     * This option can be useful if you have multiple requests running in
     * parallel for the same session (e.g. AJAX) and you are using
     * non-thread-safe collections (e.g. {@link java.util.ArrayList} or
     * {@link java.util.HashMap}). In this case, your application might modify a
     * collection while it's being serialized for backup in memcached.
     * </p>
     * <p>
     * <strong>Note:</strong> This must be supported by the TranscoderFactory
     * specified via {@link #setTranscoderFactoryClass(String)}.
     * </p>
     */
    private boolean _copyCollectionsForSerialization = false;

    private String _customConverterClassNames;

    private boolean _enableStatistics = true;

    // -------------------- END configuration properties --------------------

    private Statistics _statistics;

    /*
     * the memcached client
     */
    private MemcachedClient _memcached;

    /*
     * findSession may be often called in one request. If a session is requested
     * that we don't have locally stored each findSession invocation would
     * trigger a memcached request - this would open the door for DOS attacks...
     *
     * this solution: use a LRUCache with a timeout to store, which session had
     * been requested in the last <n> millis.
     */
    private LRUCache<String, Boolean> _missingSessionsCache;

    private NodeIdService _nodeIdService;

    //private LRUCache<String, String> _relocatedSessions;

    /*
     * we have to implement rejectedSessions - not sure why
     */
    private int _rejectedSessions;

    private TranscoderService _transcoderService;

    private TranscoderFactory _transcoderFactory;

    private SerializingTranscoder _upgradeSupportTranscoder;

    /**
     * Return descriptive information about this Manager implementation and the
     * corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     *
     * @return the info string
     */
    @Override
    public String getInfo() {
        return INFO;
    }

    /**
     * Return the descriptive short name of this Manager implementation.
     *
     * @return the short name
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init() {
        init( null );
    }

    /**
     * Initialize this manager. The memcachedClient parameter is there for testing
     * purposes. If the memcachedClient is provided it's used, otherwise a "real"/new
     * memcached client is created based on the configuration (like {@link #setMemcachedNodes(String)} etc.).
     *
     * @param memcachedClient the memcached client to use, for normal operations this should be <code>null</code>.
     */
    void init( final MemcachedClient memcachedClient ) {
        _log.info( getClass().getSimpleName() + " starts initialization... (configured" +
        		" nodes definition " + _memcachedNodes + ", failover nodes " + _failoverNodes + ")" );

        if ( initialized ) {
            return;
        }

        super.init();

        _statistics = Statistics.create( _enableStatistics );

        /* add the valve for tracking requests for that the session must be sent
         * to memcached
         */
        getContainer().getPipeline().addValve( new SessionTrackerValve( _requestUriIgnorePattern, this, _statistics ) );

        /* init memcached
         */

        if ( !NODES_PATTERN.matcher( _memcachedNodes ).matches() ) {
            throw new IllegalArgumentException( "Configured memcachedNodes attribute has wrong format, must match " + NODES_REGEX );
        }

        final List<String> nodeIds = new ArrayList<String>();
        final Set<String> allNodeIds = new HashSet<String>();
        final Matcher matcher = NODE_PATTERN.matcher( _memcachedNodes );
        final List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();
        final Map<InetSocketAddress, String> address2Ids = new HashMap<InetSocketAddress, String>();
        while ( matcher.find() ) {
            initHandleNodeDefinitionMatch( matcher, addresses, address2Ids, nodeIds, allNodeIds );
        }

        final List<String> failoverNodeIds = initFailoverNodes( nodeIds );

        if ( nodeIds.isEmpty() ) {
            throw new IllegalArgumentException( "All nodes are also configured as failover nodes,"
                    + " this is a configuration failure. In this case, you probably want to leave out the failoverNodes." );
        }

        _memcached = memcachedClient != null ? memcachedClient : createMemcachedClient( addresses, address2Ids, _statistics );

        /* create the missing sessions cache
         */
        _missingSessionsCache = new LRUCache<String, Boolean>( 200, 500 );
        _nodeIdService = new NodeIdService( createNodeAvailabilityCache( allNodeIds.size(), 1000 ), nodeIds, failoverNodeIds );

        _transcoderService = createTranscoderService( _statistics );

        _upgradeSupportTranscoder = getTranscoderFactory().createSessionTranscoder( this );

        _log.info( getClass().getSimpleName() + " finished initialization, have node ids " + nodeIds + " and failover node ids " + failoverNodeIds );

    }

    private TranscoderService createTranscoderService( final Statistics statistics ) {
        return new TranscoderService( getTranscoderFactory().createTranscoder( this ) );
    }

    protected TranscoderFactory getTranscoderFactory() {
        if ( _transcoderFactory == null ) {
            try {
                _transcoderFactory = createTranscoderFactory();
            } catch ( final Exception e ) {
                throw new RuntimeException( "Could not create transcoder factory.", e );
            }
        }
        return _transcoderFactory;
    }

    private MemcachedClient createMemcachedClient( final List<InetSocketAddress> addresses,
            final Map<InetSocketAddress, String> address2Ids,
            final Statistics statistics ) {
        try {
            return new MemcachedClient( new SuffixLocatorConnectionFactory(
                    new MapBasedResolver( address2Ids ), _sessionIdFormat, statistics ), addresses );
        } catch ( final Exception e ) {
            throw new RuntimeException( "Could not create memcached client", e );
        }
    }

    private TranscoderFactory createTranscoderFactory() throws InstantiationException, IllegalAccessException {
        log.info( "Starting with transcoder factory " + _transcoderFactoryClass.getName() );
        final TranscoderFactory transcoderFactory = _transcoderFactoryClass.newInstance();
        transcoderFactory.setCopyCollectionsForSerialization( _copyCollectionsForSerialization );
        if ( _customConverterClassNames != null ) {
            _log.info( "Loading custom converter classes " + _customConverterClassNames );
            transcoderFactory.setCustomConverterClassNames( _customConverterClassNames.split( ", " ) );
        }
        return transcoderFactory;
    }

    private NodeAvailabilityCache<String> createNodeAvailabilityCache( final int size, final long ttlInMillis ) {
        return new NodeAvailabilityCache<String>( size, ttlInMillis, new CacheLoader<String>() {

            public boolean isNodeAvailable( final String key ) {
                try {
                    _memcached.get( _sessionIdFormat.createSessionId( "ping", key ) );
                    return true;
                } catch ( final Exception e ) {
                    return false;
                }
            }

        } );
    }

    private List<String> initFailoverNodes( final List<String> nodeIds ) {
        final List<String> failoverNodeIds = new ArrayList<String>();
        if ( _failoverNodes != null && _failoverNodes.trim().length() != 0 ) {
            final String[] failoverNodes = _failoverNodes.split( " |," );
            for ( final String failoverNode : failoverNodes ) {
                final String nodeId = failoverNode.trim();
                if ( !nodeIds.remove( nodeId ) ) {
                    throw new IllegalArgumentException( "Invalid failover node id " + nodeId + ": "
                            + "not existing in memcachedNodes '" + _memcachedNodes + "'." );
                }
                failoverNodeIds.add( nodeId );
            }
        }
        return failoverNodeIds;
    }

    private void initHandleNodeDefinitionMatch( final Matcher matcher, final List<InetSocketAddress> addresses,
            final Map<InetSocketAddress, String> address2Ids, final List<String> nodeIds, final Set<String> allNodeIds ) {
        final String nodeId = matcher.group( 1 );
        nodeIds.add( nodeId );
        allNodeIds.add( nodeId );

        final String hostname = matcher.group( 2 );
        final int port = Integer.parseInt( matcher.group( 3 ) );
        final InetSocketAddress address = new InetSocketAddress( hostname, port );
        addresses.add( address );

        address2Ids.put( address, nodeId );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setContainer( final Container container ) {

        // De-register from the old Container (if any)
        if ( this.container != null && this.container instanceof Context ) {
            ( (Context) this.container ).removePropertyChangeListener( this );
        }

        // Default processing provided by our superclass
        super.setContainer( container );

        // Register with the new Container (if any)
        if ( this.container != null && this.container instanceof Context ) {
            setMaxInactiveInterval( ( (Context) this.container ).getSessionTimeout() * 60 );
            ( (Context) this.container ).addPropertyChangeListener( this );
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected synchronized String generateSessionId() {
        return _sessionIdFormat.createSessionId( super.generateSessionId(), _nodeIdService.getMemcachedNodeId() );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expireSession( final String sessionId ) {
        _log.debug( "expireSession invoked: " + sessionId );
        super.expireSession( sessionId );
        if ( _sessionIdFormat.isValid( sessionId ) ) {
            _memcached.delete( sessionId );
        }
    }

    /**
     * Return the active Session, associated with this Manager, with the
     * specified session id (if any); otherwise return <code>null</code>.
     *
     * @param id
     *            The session id for the session to be returned
     * @return the session or <code>null</code> if no session was found locally
     *         or in memcached.
     *
     * @exception IllegalStateException
     *                if a new session cannot be instantiated for any reason
     * @exception IOException
     *                if an input/output error occurs while processing this
     *                request
     */
    @Override
    public Session findSession( final String id ) throws IOException {
        Session result = super.findSession( id );
        if ( result == null && _missingSessionsCache.get( id ) == null ) {
            result = loadFromMemcached( id );
            if ( result != null ) {
                add( result );
            } else {
                _missingSessionsCache.put( id, Boolean.TRUE );
            }
        }
        //        if ( result == null ) {
        //            final String relocatedSessionId = _relocatedSessions.get( id );
        //            if ( relocatedSessionId != null ) {
        //                result = findSession( relocatedSessionId );
        //            }
        //        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Session createSession( final String sessionId ) {
        _log.debug( "createSession invoked: " + sessionId );

        Session session = null;

        if ( sessionId != null ) {
            session = loadFromMemcached( sessionId );
        }

        if ( session == null ) {

            session = createEmptySession();
            session.setNew( true );
            session.setValid( true );
            session.setCreationTime( System.currentTimeMillis() );
            session.setMaxInactiveInterval( this.maxInactiveInterval );
            session.setId( generateSessionId() );

            if ( _log.isDebugEnabled() ) {
                _log.debug( "Created new session with id " + session.getId() );
            }

        }

        sessionCounter++;

        return ( session );

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MemcachedBackupSession createEmptySession() {
        return new MemcachedBackupSession( this );
    }

    /**
     * {@inheritDoc}
     */
    public String determineSessionIdForBackup( final Session session ) {
        return getOrCreateBackupSessionTask( (MemcachedBackupSession) session ).determineSessionIdForBackup();
    }

    /**
     * Store the provided session in memcached if the session was modified
     * or if the session needs to be relocated.
     *
     * @param session
     *            the session to save
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResultStatus}
     */
    public BackupResultStatus backupSession( final Session session ) {
        return getOrCreateBackupSessionTask( (MemcachedBackupSession) session ).backupSession();

    }

    private BackupSessionTask getOrCreateBackupSessionTask( final MemcachedBackupSession session ) {
        if ( session.getBackupTask() == null ) {
            session.setBackupTask( new BackupSessionTask( session, _transcoderService, _sessionBackupAsync, _sessionBackupTimeout,
                    _memcached, _nodeIdService, _statistics ) );
        }
        return session.getBackupTask();
    }

    protected Session loadFromMemcached( final String sessionId ) {
        if ( !_sessionIdFormat.isValid( sessionId ) ) {
            return null;
        }
        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
        if ( !_nodeIdService.isNodeAvailable( nodeId ) ) {
            _log.debug( "Asked for session " + sessionId + ", but the related"
                    + " memcached node is still marked as unavailable (won't load from memcached)." );
        } else {
            _log.debug( "Loading session from memcached: " + sessionId );
            try {

                final long start = System.currentTimeMillis();

                /* In the previous version (<1.2) the session was completely serialized by
                 * custom Transcoder implementations.
                 * Such sessions have set the SERIALIZED flag (from SerializingTranscoder) so that
                 * they get deserialized by BaseSerializingTranscoder.deserialize or the appropriate
                 * specializations.
                 */
                final Object object = _memcached.get( sessionId, _upgradeSupportTranscoder );

                if ( _log.isDebugEnabled() ) {
                    if ( object == null ) {
                        _log.debug( "Session " + sessionId + " not found in memcached." );
                    } else {
                        _log.debug( "Found session with id " + sessionId );
                    }
                }
                _nodeIdService.setNodeAvailable( nodeId, true );

                if ( object instanceof MemcachedBackupSession ) {
                    _statistics.getLoadFromMemcachedProbe().registerSince( start );
                    return (Session) object;
                }
                else {
                    final MemcachedBackupSession result = _transcoderService.deserialize( (byte[]) object, getContainer().getRealm(), this );
                    if ( object != null ) {
                        _statistics.getLoadFromMemcachedProbe().registerSince( start );
                    }
                    return result;
                }
            } catch ( final NodeFailureException e ) {
                _log.warn( "Could not load session with id " + sessionId + " from memcached." );
                _nodeIdService.setNodeAvailable( nodeId, false );
            } catch ( final Exception e ) {
                _log.warn( "Could not load session with id " + sessionId + " from memcached.", e );
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove( final Session session ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "remove invoked, session.relocate:  " + session.getNote( SessionTrackerValve.RELOCATE ) +
                    ", node failure: " + session.getNote( NODE_FAILURE ) +
                    ", id: " + session.getId() );
        }
        if ( _sessionIdFormat.isValid( session.getId() )
                && session.getNote( NODE_FAILURE ) != Boolean.TRUE ) {
            try {
                _log.debug( "Deleting session from memcached: " + session.getId() );
                _memcached.delete( session.getId() );
            } catch ( final NodeFailureException e ) {
                /* We can ignore this */
            }
        }
        super.remove( session );
    }

    /**
     * {@inheritDoc}
     */
    public int getRejectedSessions() {
        return _rejectedSessions;
    }

    /**
     * {@inheritDoc}
     */
    public void load() throws ClassNotFoundException, IOException {
    }

    /**
     * {@inheritDoc}
     */
    public void setRejectedSessions( final int rejectedSessions ) {
        _rejectedSessions = rejectedSessions;
    }

    /**
     * {@inheritDoc}
     */
    public void unload() throws IOException {
    }

    /**
     * Set the memcached nodes.
     * <p>
     * E.g. <code>n1.localhost:11211 n2.localhost:11212</code>
     * </p>
     *
     * @param memcachedNodes
     *            the memcached node definitions, whitespace separated
     */
    public void setMemcachedNodes( final String memcachedNodes ) {
        _memcachedNodes = memcachedNodes;
    }

    /**
     * The node ids of memcached nodes, that shall only be used for session
     * backup by this tomcat/manager, if there are no other memcached nodes
     * left. Node ids are separated by whitespace.
     * <p>
     * E.g. <code>n1 n2</code>
     * </p>
     *
     * @param failoverNodes
     *            the failoverNodes to set, whitespace separated
     */
    public void setFailoverNodes( final String failoverNodes ) {
        _failoverNodes = failoverNodes;
    }

    /**
     * Set the regular expression for request uris to ignore for session backup.
     * This should include static resources like images, in the case they are
     * served by tomcat.
     * <p>
     * E.g. <code>.*\.(png|gif|jpg|css|js)$</code>
     * </p>
     *
     * @param requestUriIgnorePattern
     *            the requestUriIgnorePattern to set
     * @author Martin Grotzke
     */
    public void setRequestUriIgnorePattern( final String requestUriIgnorePattern ) {
        _requestUriIgnorePattern = requestUriIgnorePattern;
    }

    /**
     * The class of the factory that creates the
     * {@link net.spy.memcached.transcoders.Transcoder} to use for serializing/deserializing
     * sessions to/from memcached (requires a default/no-args constructor).
     * The default value is the {@link JavaSerializationTranscoderFactory} class
     * (used if this configuration attribute is not specified).
     * <p>
     * After the {@link TranscoderFactory} instance was created from the specified class,
     * {@link TranscoderFactory#setCopyCollectionsForSerialization(boolean)}
     * will be invoked with the currently set <code>copyCollectionsForSerialization</code> propery, which
     * has either still the default value (<code>false</code>) or the value provided via
     * {@link #setCopyCollectionsForSerialization(boolean)}.
     * </p>
     *
     * @param transcoderFactoryClassName the {@link TranscoderFactory} class name.
     */
    public void setTranscoderFactoryClass( final String transcoderFactoryClassName ) {
        try {
            _transcoderFactoryClass = Class.forName( transcoderFactoryClassName ).asSubclass( TranscoderFactory.class );
        } catch ( final ClassNotFoundException e ) {
            _log.error( "The transcoderFactoryClass (" + transcoderFactoryClassName + ") could not be found" );
            throw new RuntimeException( e );
        }
    }

    /**
     * Specifies, if iterating over collection elements shall be done on a copy
     * of the collection or on the collection itself. The default value is <code>false</code>
     * (used if this configuration attribute is not specified).
     * <p>
     * This option can be useful if you have multiple requests running in
     * parallel for the same session (e.g. AJAX) and you are using
     * non-thread-safe collections (e.g. {@link java.util.ArrayList} or
     * {@link java.util.HashMap}). In this case, your application might modify a
     * collection while it's being serialized for backup in memcached.
     * </p>
     * <p>
     * <strong>Note:</strong> This must be supported by the {@link TranscoderFactory}
     * specified via {@link #setTranscoderFactoryClass(String)}: after the {@link TranscoderFactory} instance
     * was created from the specified class, {@link TranscoderFactory#setCopyCollectionsForSerialization(boolean)}
     * will be invoked with the provided <code>copyCollectionsForSerialization</code> value.
     * </p>
     *
     * @param copyCollectionsForSerialization
     *            <code>true</code>, if iterating over collection elements shall be done
     *            on a copy of the collection, <code>false</code> if the collections own iterator
     *            shall be used.
     */
    public void setCopyCollectionsForSerialization( final boolean copyCollectionsForSerialization ) {
        _copyCollectionsForSerialization = copyCollectionsForSerialization;
    }

    /**
     * Custom converter allow you to provide custom serialization of application specific
     * types. Multiple converter classes are separated by comma (with optional space following the comma).
     * <p>
     * This option is useful if reflection based serialization is very verbose and you want
     * to provide a more efficient serialization for a specific type.
     * </p>
     * <p>
     * <strong>Note:</strong> This must be supported by the {@link TranscoderFactory}
     * specified via {@link #setTranscoderFactoryClass(String)}: after the {@link TranscoderFactory} instance
     * was created from the specified class, {@link TranscoderFactory#setCustomConverterClassNames(String[])}
     * is invoked with the provided custom converter class names.
     * </p>
     * <p>Requirements regarding the specific custom converter classes depend on the
     * actual serialization strategy, but a common requirement would be that they must
     * provide a default/no-args constructor.<br/>
     * For more details have a look at
     * <a href="http://code.google.com/p/memcached-session-manager/wiki/SerializationStrategies">SerializationStrategies</a>.
     * </p>
     *
     * @param customConverterClassNames a list of class names separated by comma
     */
    public void setCustomConverter( final String customConverterClassNames ) {
        _customConverterClassNames = customConverterClassNames;
    }

    /**
     * Specifies if statistics (like number of requests with/without session) shall be
     * gathered. Default value of this property is <code>true</code>.
     * <p>
     * Statistics will be available via jmx and the Manager mbean (
     * e.g. in the jconsole mbean tab open the attributes node of the
     * <em>Catalina/Manager/&lt;context-path&gt;/&lt;host name&gt;</em>
     * mbean and check for <em>msmStat*</em> values.
     * </p>
     *
     * @param enableStatistics <code>true</code> if statistics shall be gathered.
     */
    public void setEnableStatistics( final boolean enableStatistics ) {
        _enableStatistics = enableStatistics;
    }

    /**
     * {@inheritDoc}
     */
    public void addLifecycleListener( final LifecycleListener arg0 ) {
        _lifecycle.addLifecycleListener( arg0 );
    }

    /**
     * {@inheritDoc}
     */
    public LifecycleListener[] findLifecycleListeners() {
        return _lifecycle.findLifecycleListeners();
    }

    /**
     * {@inheritDoc}
     */
    public void removeLifecycleListener( final LifecycleListener arg0 ) {
        _lifecycle.removeLifecycleListener( arg0 );
    }

    /**
     * {@inheritDoc}
     */
    public void start() throws LifecycleException {
        if ( !initialized ) {
            init();
        }
    }

    /**
     * {@inheritDoc}
     */
    public void stop() throws LifecycleException {
        if ( initialized ) {
            _memcached.shutdown();
            destroy();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void backgroundProcess() {
        updateExpirationInMemcached();
        super.backgroundProcess();
    }

    private void updateExpirationInMemcached() {
        final Session[] sessions = findSessions();
        final int delay = getContainer().getBackgroundProcessorDelay();
        for ( final Session s : sessions ) {
            final MemcachedBackupSession session = (MemcachedBackupSession) s;
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Checking session " + session.getId() + ": " +
                        "\n- isValid: " + session.isValidInternal() +
                        "\n- isExpiring: " + session.isExpiring() +
                        "\n- isBackupRunning: " + session.isBackupRunning() +
                        "\n- isExpirationUpdateRunning: " + session.isExpirationUpdateRunning() +
                        "\n- wasAccessedSinceLastBackup: " + session.wasAccessedSinceLastBackup() +
                        "\n- memcachedExpirationTime: " + session.getMemcachedExpirationTime() );
            }
            if ( session.isValidInternal()
                    && !session.isExpiring()
                    && !session.isBackupRunning()
                    && !session.isExpirationUpdateRunning()
                    && session.wasAccessedSinceLastBackup()
                    && session.getMemcachedExpirationTime() <= 2 * delay ) {
                try {
                    getOrCreateBackupSessionTask( session ).updateExpiration();
                } catch ( final Throwable e ) {
                    _log.info( "Could not update expiration in memcached for session " + session.getId() );
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void propertyChange( final PropertyChangeEvent event ) {

        // Validate the source of this event
        if ( !( event.getSource() instanceof Context ) ) {
            return;
        }

        // Process a relevant property change
        if ( event.getPropertyName().equals( "sessionTimeout" ) ) {
            try {
                setMaxInactiveInterval( ( (Integer) event.getNewValue() ).intValue() * 60 );
            } catch ( final NumberFormatException e ) {
                _log.warn( "standardManager.sessionTimeout: " + event.getNewValue().toString() );
            }
        }

    }

    /**
     * Specifies if the session shall be stored asynchronously in memcached as
     * {@link MemcachedClient#set(String, int, Object)} supports it. If this is
     * false, the timeout set via {@link #setSessionBackupTimeout(int)} is
     * evaluated.
     * <p>
     * Notice: if the session backup is done asynchronously, it is possible that
     * a session cannot be stored in memcached and we don't notice that -
     * therefore the session would not get relocated to another memcached node.
     * </p>
     * <p>
     * By default this property is set to <code>false</code> - the session
     * backup is performed synchronously.
     * </p>
     *
     * @param sessionBackupAsync
     *            the sessionBackupAsync to set
     */
    public void setSessionBackupAsync( final boolean sessionBackupAsync ) {
        _sessionBackupAsync = sessionBackupAsync;
    }

    /**
     * The timeout in milliseconds after that a session backup is considered as
     * beeing failed.
     * <p>
     * This property is only evaluated if sessions are stored synchronously (set
     * via {@link #setSessionBackupAsync(boolean)}).
     * </p>
     * <p>
     * The default value is <code>100</code> millis.
     *
     * @param sessionBackupTimeout
     *            the sessionBackupTimeout to set (milliseconds)
     */
    public void setSessionBackupTimeout( final int sessionBackupTimeout ) {
        _sessionBackupTimeout = sessionBackupTimeout;
    }

    // ----------------------- protected getters/setters for testing ------------------

    /**
     * Set the {@link TranscoderService} that is used by this manager and the {@link BackupSessionTask}.
     *
     * @param transcoderService the transcoder service to use.
     */
    void setTranscoderService( final TranscoderService transcoderService ) {
        _transcoderService = transcoderService;
    }

    /**
     * Just for testing, DON'T USE THIS OTHERWISE!
     */
    void resetInitialized() {
        initialized = false;
    }

    /**
     * Return the currently configured node ids - just for testing.
     * @return the list of node ids.
     */
    List<String> getNodeIds() {
        return _nodeIdService.getNodeIds();
    }
    /**
     * Return the currently configured failover node ids - just for testing.
     * @return the list of failover node ids.
     */
    List<String> getFailoverNodeIds() {
        return _nodeIdService.getFailoverNodeIds();
    }

    // -------------------------  statistics via jmx ----------------

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithBackup()
     */
    public long getMsmStatNumBackups() {
        return _statistics.getRequestsWithBackup();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithBackupFailure()
     */
    public long getMsmStatNumBackupFailures() {
        return _statistics.getRequestsWithBackupFailure();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithBackupRelocation()
     */
    public long getMsmStatBackupRelocations() {
        return _statistics.getRequestsWithBackupRelocation();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSession()
     */
    public long getMsmStatNumRequestsWithoutSession() {
        return _statistics.getRequestsWithoutSession();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSessionAccess()
     */
    public long getMsmStatNumNoSessionAccess() {
        return _statistics.getRequestsWithoutSessionAccess();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSessionModification()
     */
    public long getMsmStatNumNoSessionModification() {
        return _statistics.getRequestsWithoutSessionModification();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithSession()
     */
    public long getMsmStatNumRequestsWithSession() {
        return _statistics.getRequestsWithSession();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getSessionsLoadedFromMemcached()
     */
    public long getMsmStatNumSessionsLoadedFromMemcached() {
        return _statistics.getSessionsLoadedFromMemcached();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that took the attributes serialization.
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatAttributesSerializationInfo() {
        return _statistics.getAttributesSerializationProbe().getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that session backups took (excluding backups where a session
     * was relocated).
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatBackupInfo() {
        return _statistics.getBackupProbe().getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that session relocations took.
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatBackupRelocationInfo() {
        return _statistics.getBackupRelocationProbe().getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that loading sessions from memcached took (including deserialization).
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatSessionsLoadedFromMemcachedInfo() {
        return _statistics.getLoadFromMemcachedProbe().getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the size of the data that was sent to memcached.
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatCachedDataSizeInfo() {
        return _statistics.getCachedDataSizeProbe().getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that storing data in memcached took (excluding serialization,
     * including compression).
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatMemcachedUpdateInfo() {
        return _statistics.getMemcachedUpdateProbe().getInfo();
    }

}