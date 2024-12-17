package com.igot.cb.transactional.cassandrautils;

import com.datastax.driver.core.*;
import com.datastax.driver.core.policies.DefaultRetryPolicy;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PropertiesCache;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * @author Mahesh RV
 * @author Ruksana
 * <p>
 * Manages Cassandra connections and sessions.
 */
@Component
public class CassandraConnectionManagerImpl implements CassandraConnectionManager {
    private final Logger logger = LogManager.getLogger(getClass());
    private final Map<String, Session> cassandraSessionMap = new ConcurrentHashMap<>(2);
    private Cluster cluster;

    /**
     * Method invoked after bean creation for initialization
     */
    @PostConstruct
    private void initialize() {
        logger.info("Initializing CassandraConnectionManager...");
        registerShutdownHook();
        createCassandraConnection();
        initializeSessions();
        logger.info("CassandraConnectionManager initialized.");
    }

    /**
     * Retrieves a session for the specified keyspace.
     * If a session for the keyspace already exists, returns it; otherwise, creates a new session.
     *
     * @param keyspace The keyspace for which to retrieve the session.
     * @return The session object for the specified keyspace.
     */
    public Session getSession(String keyspace) {
        return cassandraSessionMap.computeIfAbsent(keyspace, k -> cluster.connect(keyspace));
    }

    /**
     * Creates a Cassandra connection based on properties
     */
    private void createCassandraConnection() {
        try {
            PropertiesCache cache = PropertiesCache.getInstance();
            PoolingOptions poolingOptions = new PoolingOptions();
            poolingOptions.setCoreConnectionsPerHost(HostDistance.LOCAL, Integer.parseInt(cache.getProperty(Constants.CORE_CONNECTIONS_PER_HOST_FOR_LOCAL)));
            poolingOptions.setMaxConnectionsPerHost(HostDistance.LOCAL, Integer.parseInt(cache.getProperty(Constants.MAX_CONNECTIONS_PER_HOST_FOR_LOCAL)));
            poolingOptions.setCoreConnectionsPerHost(HostDistance.REMOTE, Integer.parseInt(cache.getProperty(Constants.CORE_CONNECTIONS_PER_HOST_FOR_REMOTE)));
            poolingOptions.setMaxConnectionsPerHost(HostDistance.REMOTE, Integer.parseInt(cache.getProperty(Constants.MAX_CONNECTIONS_PER_HOST_FOR_REMOTE)));
            poolingOptions.setMaxRequestsPerConnection(HostDistance.LOCAL, Integer.parseInt(cache.getProperty(Constants.MAX_REQUEST_PER_CONNECTION)));
            poolingOptions.setHeartbeatIntervalSeconds(Integer.parseInt(cache.getProperty(Constants.HEARTBEAT_INTERVAL)));
            poolingOptions.setPoolTimeoutMillis(Integer.parseInt(cache.getProperty(Constants.POOL_TIMEOUT)));
            String[] hosts = StringUtils.split(cache.getProperty(Constants.CASSANDRA_CONFIG_HOST), ",");
            cluster = createCluster(hosts, poolingOptions);
            logClusterDetails(cluster);
        } catch (Exception e) {
            logger.error("Error creating Cassandra connection", e);
            throw new CustomException("Internal Server Error", e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Creates a Cluster object with specified hosts and pooling options
     *
     * @param hosts          - Cassandra host configuration
     * @param poolingOptions -   // Configure connection pooling options
     * @return - Cluster object with specified hosts and pooling options
     */
    private static Cluster createCluster(String[] hosts, PoolingOptions poolingOptions) {
        Cluster.Builder builder = Cluster.builder()
                .addContactPoints(hosts)
                .withProtocolVersion(ProtocolVersion.V3)
                .withRetryPolicy(DefaultRetryPolicy.INSTANCE)
                .withTimestampGenerator(new AtomicMonotonicTimestampGenerator())
                .withPoolingOptions(poolingOptions);

        ConsistencyLevel consistencyLevel = getConsistencyLevel();
        if (consistencyLevel != null) {
            builder.withQueryOptions(new QueryOptions().setConsistencyLevel(consistencyLevel));
        }

        return builder.build();
    }

    /**
     * Retrieves consistency level from properties
     *
     * @return -consistency level from properties
     */
    private static ConsistencyLevel getConsistencyLevel() {
        String consistency = PropertiesCache.getInstance().readProperty(Constants.SUNBIRD_CASSANDRA_CONSISTENCY_LEVEL);
        if (StringUtils.isBlank(consistency)) return null;

        try {
            return ConsistencyLevel.valueOf(consistency.toUpperCase());
        } catch (IllegalArgumentException exception) {
            LogManager.getLogger(CassandraConnectionManagerImpl.class)
                    .info("Exception occurred with error message = {}", exception.getMessage());
        }
        return null;
    }

    /**
     * Initializes sessions for predefined keyspaces
     */
    private void initializeSessions() {
        List<String> keyspacesList = Collections.singletonList(Constants.KEYSPACE_SUNBIRD);
        for (String keyspace : keyspacesList) {
            getSession(keyspace);
        }
    }

    /**
     * Registers a shutdown hook to clean-up resources
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupResources));
        logger.info("Cassandra shutdown hook registered.");
    }

    /**
     * Cleans up Cassandra resources during shutdown
     */
    private void cleanupResources() {
        logger.info("Starting resource cleanup for Cassandra...");
        cassandraSessionMap.values().forEach(Session::close);
        if (cluster != null) {
            cluster.close();
        }
        logger.info("Resource cleanup for Cassandra completed.");
    }

    private void logClusterDetails(Cluster cluster) {
        final Metadata metadata = cluster.getMetadata();
        logger.info("Connected to cluster: {}", metadata.getClusterName());
        metadata.getAllHosts().forEach(host ->
                logger.info("Datacenter: {}; Host: {}; Rack: {}", host.getDatacenter(), host.getAddress(), host.getRack()));
    }
}