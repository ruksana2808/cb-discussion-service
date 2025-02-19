package com.igot.cb.pores.elasticsearch.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class EsConfig  {
    @Value("${elasticsearch.host}")
    private String elasticsearchHost;

    @Value("${elasticsearch.port}")
    private int elasticsearchPort;

    @Value("${elasticsearch.username}")
    private String elasticsearchUsername;

    @Value("${elasticsearch.password}")
    private String elasticsearchPassword;

    @Value("${elasticsearch.sbESClient.host}")
    private String sbESClientHost;

    @Value("${elasticsearch.sbESClient.port}")
    private int sbESClientPort;

    @Value("${elasticsearch.sbESClient.username}")
    private String sbESClientUsername;

    @Value("${elasticsearch.sbESClient.password}")
    private String sbESClientPassword;

//    @Override
    @Bean(name = "elasticsearchClient")
    public RestHighLevelClient elasticsearchClient() {
        return createClient(elasticsearchHost, elasticsearchPort, elasticsearchUsername, elasticsearchPassword);
    }

    @Bean(name = "sbESClient")
    public RestHighLevelClient sbESClient() {
        return createClient(sbESClientHost, sbESClientPort, sbESClientUsername, sbESClientPassword);
    }

    private RestHighLevelClient createClient(String host, int port, String username, String password) {
        final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));

        RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, "http"))
            .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));

        return new RestHighLevelClient(builder);
    }
}
