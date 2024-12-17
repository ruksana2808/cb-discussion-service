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




