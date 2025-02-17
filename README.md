# cb-community-service
cb-community-service

## Prerequisites
Postgres, cassandra, redis, elasticsearch

## Setup
portforward the postgres, cassandra, redis and elasticsearch using following commands:

```shell
 ssh -L 0.0.0.0:6379:redis_ip:6379 userName@jumphost_ip -p 9822
```
```shell
 ssh -L 0.0.0.0:5432:postgres_ip:5432 userName@jumphost_ip -p 9822
```
```shell
 ssh -L 0.0.0.0:9042:cassandra_ip:9042 user@jumphost_ip -p 9822
```
```shell
 ssh -L 0.0.0.0:9200:es_ip:9200 user@jumphost_ip -p 9822
```
clean and build the appplication

## Tables script
Tables in postgres:
communities in the sunbird Db to store the metaData of community:
```shell
 CREATE TABLE IF NOT EXISTS public.communities
(
    community_id character varying(255) COLLATE pg_catalog."default" NOT NULL,
    data jsonb,
    created_on timestamp without time zone,
    updated_on timestamp without time zone,
    created_by character varying(255) COLLATE pg_catalog."default",
    is_active boolean,
    PRIMARY KEY (community_id)
);
```
Tables in cassandra:
user_community in sunbird cassandra to store the users associated with the community
```shell
 CREATE TABLE user_community ( userId UUID, communityId UUID, stats boolean, lastUpdatedAt timestamp, primary key (userId, communityId));
```
community_user_lookup in sunbird cassandra - to fetch the list of users joined in a community
```shell
 CREATE TABLE community_user_lookup (

    communityId UUID,

    userId UUID,

    status BOOLEAN,  -- `true` for active, `false` for inactive

    PRIMARY KEY (communityId, userId)

);
```
user_reported_communities in sunbird cassandra - to fetch the list of users joined in a community
```shell
 CREATE TABLE user_reported_communities (
    userid text,
    communityid text,
    createdon timestamp,
    reason text,
    PRIMARY KEY (userid, communityid)
) WITH CLUSTERING ORDER BY (communityid ASC)
    AND bloom_filter_fp_chance = 0.01
    AND caching = {'keys': 'ALL', 'rows_per_partition': 'NONE'}
    AND comment = ''
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy', 'max_threshold': '32', 'min_threshold': '4'}
    AND compression = {'chunk_length_in_kb': '64', 'class': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND crc_check_chance = 1.0
    AND dclocal_read_repair_chance = 0.1
    AND default_time_to_live = 0
    AND gc_grace_seconds = 864000
    AND max_index_interval = 2048
    AND memtable_flush_period_in_ms = 0
    AND min_index_interval = 128
    AND read_repair_chance = 0.0
    AND speculative_retry = '99PERCENTILE';
```
communities_reportedby_user in sunbird cassandra - to fetch the list of users joined in a community
```shell
 CREATE TABLE sunbird.communities_reportedby_user (
    communityid text,
    userid text,
    createdon timestamp,
    reason text,
    PRIMARY KEY (communityid, userid)
) WITH CLUSTERING ORDER BY (userid ASC)
    AND bloom_filter_fp_chance = 0.01
    AND caching = {'keys': 'ALL', 'rows_per_partition': 'NONE'}
    AND comment = ''
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy', 'max_threshold': '32', 'min_threshold': '4'}
    AND compression = {'chunk_length_in_kb': '64', 'class': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND crc_check_chance = 1.0
    AND dclocal_read_repair_chance = 0.1
    AND default_time_to_live = 0
    AND gc_grace_seconds = 864000
    AND max_index_interval = 2048
    AND memtable_flush_period_in_ms = 0
    AND min_index_interval = 128
    AND read_repair_chance = 0.0
    AND speculative_retry = '99PERCENTILE';
```





