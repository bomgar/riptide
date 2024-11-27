package org.zalando.riptide.autoconfigure;

import com.google.gag.annotation.remark.Hack;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.cache.HttpCacheStorage;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.cache.CacheConfig;
import org.apache.hc.client5.http.impl.cache.CachingHttpClientBuilder;
import org.apache.hc.client5.http.impl.cache.CachingHttpClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.springframework.boot.ssl.SslBundles;
import org.zalando.riptide.autoconfigure.RiptideProperties.Caching;
import org.zalando.riptide.autoconfigure.RiptideProperties.Caching.Heuristic;
import org.zalando.riptide.autoconfigure.RiptideProperties.CertificatePinning;
import org.zalando.riptide.autoconfigure.RiptideProperties.CertificatePinning.Keystore;
import org.zalando.riptide.autoconfigure.RiptideProperties.Client;
import org.zalando.riptide.autoconfigure.RiptideProperties.Connections;
import org.zalando.riptide.autoconfigure.RiptideProperties.SslBundleUsage;

import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static java.lang.String.format;

@SuppressWarnings("unused")
@Slf4j
final class HttpClientFactory {

    private HttpClientFactory() {

    }

    public static HttpClientConnectionManager createHttpClientConnectionManager(final Client client)
            throws GeneralSecurityException, IOException {

        final Connections connections = client.getConnections();

        final PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", PlainConnectionSocketFactory.getSocketFactory())
                        .register("https", new SSLConnectionSocketFactory(createSSLContext(client)))
                        .build());

        manager.setMaxTotal(connections.getMaxTotal());
        manager.setDefaultMaxPerRoute(connections.getMaxPerRoute());

        return manager;
    }

    public static HttpClientConnectionManager createHttpClientConnectionManagerWithSslBundle(final Client client, final String clientId, final SslBundles sslBundles) {

        final Connections connections = client.getConnections();

        final PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager(
            RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", new SSLConnectionSocketFactory(createSslContextFromSslBundle(client, clientId, sslBundles)))
                .build());

        manager.setMaxTotal(connections.getMaxTotal());
        manager.setDefaultMaxPerRoute(connections.getMaxPerRoute());

        return manager;
    }

    public static CloseableHttpClient createHttpClient(final Client client,
                                                       final List<HttpRequestInterceptor> firstRequestInterceptors,
                                                       final PoolingHttpClientConnectionManager connectionManager,
                                                       @Nullable final HttpClientCustomizer customizer,
                                                       @Nullable final Object cacheStorage) {

        final Caching caching = client.getCaching();
        final HttpClientBuilder builder = caching.getEnabled() ?
                configureCaching(caching, cacheStorage) :
                HttpClientBuilder.create();

        final Connections connections = client.getConnections();

        final ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(connections.getConnectTimeout().toTimeout())
                .setSocketTimeout(connections.getSocketTimeout().toTimeout())
                .build();

        connectionManager.setDefaultConnectionConfig(connectionConfig);

        firstRequestInterceptors.forEach(builder::addRequestInterceptorFirst);

        final RequestConfig reqConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(connections.getLeaseRequestTimeout().toTimeout())
                .build();

        builder.setConnectionManager(connectionManager)
                .setDefaultRequestConfig(reqConfig)
                .disableAutomaticRetries();

        Optional.ofNullable(customizer).ifPresent(customize(builder));

        return builder.build();
    }

    private static HttpClientBuilder configureCaching(final Caching caching,
                                                      @Nullable final Object cacheStorage) {
        final Heuristic heuristic = caching.getHeuristic();

        final CacheConfig.Builder config = CacheConfig.custom()
                .setSharedCache(caching.getShared())
                .setMaxObjectSize(caching.getMaxObjectSize())
                .setMaxCacheEntries(caching.getMaxCacheEntries());

        if (heuristic.getEnabled()) {
            config.setHeuristicCachingEnabled(true);
            config.setHeuristicCoefficient(heuristic.getCoefficient());
            config.setHeuristicDefaultLifetime(heuristic.getDefaultLifeTime().toTimeValue());
        }

        @Hack("return cast tricks classloader in case of missing httpclient-cache")
        CachingHttpClientBuilder builder = CachingHttpClients.custom()
                .setCacheConfig(config.build())
                .setHttpCacheStorage((HttpCacheStorage) cacheStorage)
                .setCacheDir(Optional.ofNullable(caching.getDirectory())
                        .map(Path::toFile)
                        .orElse(null));
        return HttpClientBuilder.class.cast(builder);
    }

    private static SSLContext createSSLContext(final Client client) throws GeneralSecurityException, IOException {
        final CertificatePinning pinning = client.getCertificatePinning();

        if (pinning.getEnabled()) {
            final Keystore keystore = pinning.getKeystore();
            final String path = keystore.getPath();
            final String password = keystore.getPassword();

            final URL resource = HttpClientFactory.class.getClassLoader().getResource(path);

            if (resource == null) {
                throw new FileNotFoundException(format("Keystore [%s] not found.", path));
            }

            try {
                return SSLContexts.custom()
                        .loadTrustMaterial(resource, password == null ? null : password.toCharArray())
                        .build();
            } catch (final Exception e) {
                log.error("Error loading keystore [{}]:", path,
                        e); // log full exception, bean initialization code swallows it
                throw e;
            }
        }

        return SSLContexts.createDefault();
    }

    protected static SSLContext createSslContextFromSslBundle(final Client client, final String clientId, final SslBundles sslBundles) {
        final SslBundleUsage sslBundleUsage = client.getSslBundleUsage();
        if(sslBundleUsage.getEnabled()) {
            final String bundleId = Optional.ofNullable(sslBundleUsage.getSslBundleId()).orElse(clientId);
            try {
                return sslBundles
                    .getBundle(bundleId)
                    .createSslContext();
            } catch (final Exception e) {
                log.error("Error loading ssl bundle [{}]:", bundleId, e);
                throw e;
            }
        }

        return SSLContexts.createDefault();
    }

    private static Consumer<HttpClientCustomizer> customize(final HttpClientBuilder builder) {
        return customizer -> customizer.customize(builder);
    }

}
