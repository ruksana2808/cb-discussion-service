package com.igot.cb;

import com.igot.cb.pores.util.PropertiesCache;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;


/**
 * @author Mahesh RV
 * @author Ruksana
 */
@EnableJpaRepositories(basePackages = {"com.igot.cb.*"})
@ComponentScan(basePackages = "com.igot.cb")
@EntityScan("com.igot.cb")
@SpringBootApplication
public class CbCommunityServiceApplication {


	public static void main(String[] args) {
		SpringApplication.run(CbCommunityServiceApplication.class, args);
	}

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate(getClientHttpRequestFactory());
    }

    private ClientHttpRequestFactory getClientHttpRequestFactory() {

      // Get the PropertiesCache instance to fetch the properties
      PropertiesCache propertiesCache = PropertiesCache.getInstance();

      // Fetch timeout and connection values from PropertiesCache
      int connectTimeout = Integer.parseInt(propertiesCache.getProperty("rest.client.connect.timeout"));
      int readTimeout = Integer.parseInt(propertiesCache.getProperty("rest.client.read.timeout"));
      int connectionRequestTimeout = Integer.parseInt(propertiesCache.getProperty("rest.client.connection.request.timeout"));
      int maxConnections = Integer.parseInt(propertiesCache.getProperty("rest.client.max.connections"));
      int maxConnectionsPerRoute = Integer.parseInt(propertiesCache.getProperty("rest.client.max.connections.per.route"));

      // Configure the RequestConfig with timeouts
      RequestConfig config = RequestConfig.custom()
          .setConnectTimeout(connectTimeout)
          .setSocketTimeout(readTimeout)
          .setConnectionRequestTimeout(connectionRequestTimeout)
          .build();

      // Configure the CloseableHttpClient with max connections
      CloseableHttpClient client = HttpClientBuilder.create()
          .setMaxConnTotal(maxConnections)
          .setMaxConnPerRoute(maxConnectionsPerRoute)
          .setDefaultRequestConfig(config)
          .build();

      // Set the read timeout on the request factory
      HttpComponentsClientHttpRequestFactory cRequestFactory = new HttpComponentsClientHttpRequestFactory(client);
      cRequestFactory.setReadTimeout(readTimeout);
      cRequestFactory.setConnectTimeout(connectTimeout);

      return cRequestFactory;
    }

}
