/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.server;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.client.ClientConfig;
import com.networknt.client.Http2Client;
import com.networknt.config.Config;
import com.networknt.monad.Failure;
import com.networknt.status.Status;
import com.networknt.utility.StringUtils;
import io.undertow.UndertowOptions;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.networknt.server.Server.ENV_PROPERTY_KEY;
import static com.networknt.server.Server.STARTUP_CONFIG_NAME;


/**
 * Default Config Loader to fetch and load configs from light config server
 *
 * @author santosh.aherkar@gmail.com
 *
 */
public class DefaultConfigLoader implements IConfigLoader{
    static final Logger logger = LoggerFactory.getLogger(DefaultConfigLoader.class);

    public static Map<String, Object> startupConfig = Config.getInstance().getJsonMapConfig(STARTUP_CONFIG_NAME);
    private static final String CENTRALIZED_MANAGEMENT = "values";
    public static final String LIGHT_ENV = "light-env";
    public static final String VERIFY_HOSTNAME_FALSE = "false";

    public static final String DEFAULT_ENV = "dev";
    public static final String DEFAULT_TARGET_CONFIGS_DIRECTORY ="src/main/resources/config";

    public static final String CONFIG_SERVER_URI = "light-config-server-uri";
    public static final String CONFIG_SERVER_CONFIGS_CONTEXT_ROOT = "/configs";
    public static final String CONFIG_SERVER_CERTS_CONTEXT_ROOT = "/certs";
    public static final String CONFIG_SERVER_FILES_CONTEXT_ROOT = "/files";
    public static final String AUTHORIZATION = "config_server_authorization";
    public static final String CLIENT_TRUSTSTORE_PASS = "config_server_client_truststore_password";
    public static final String CLIENT_TRUSTSTORE_LOC = "config_server_client_truststore_location";
    public static final String VERIFY_HOST_NAME = "config_server_client_verify_host_name";

    public static final String PROJECT_NAME = "projectName";
    public static final String PROJECT_VERSION = "projectVersion";
    public static final String SERVICE_NAME = "serviceName";
    public static final String SERVICE_VERSION = "serviceVersion";

    public static String lightEnv = null;
    public static String configServerUri = null;
    public static String targetConfigsDirectory = null;

    // An instance of Jackson ObjectMapper that can be used anywhere else for Json.
    final static ObjectMapper mapper = new ObjectMapper();
    // Using JDK 11 HTTP client to connect to the config server with bootstrap.truststore
    private static HttpClient configClient = createHttpClient();

    private static HttpClient createHttpClient() {
        String verifyHostname = getPropertyOrEnv(VERIFY_HOST_NAME);
        if(VERIFY_HOSTNAME_FALSE.equals(verifyHostname)) {
            final Properties props = System.getProperties();
            props.setProperty("jdk.internal.httpclient.disableHostnameVerification", Boolean.TRUE.toString());
        }
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(1000)) // default to 1 second timeout.
                .version(HttpClient.Version.HTTP_2)
                .sslContext(createBootstrapContext());
        return clientBuilder.build();
    }

    @Override
    public void init() {
        lightEnv = getPropertyOrEnv(LIGHT_ENV);
        if (lightEnv == null) {
            logger.warn("Warning! {} is not provided; defaulting to {}", LIGHT_ENV, DEFAULT_ENV);
            lightEnv = DEFAULT_ENV;
        }
        targetConfigsDirectory = getPropertyOrEnv(Config.LIGHT_4J_CONFIG_DIR);
        if (targetConfigsDirectory == null) {
            logger.warn("Warning! {} is not provided; defaulting to {}", Config.LIGHT_4J_CONFIG_DIR, DEFAULT_TARGET_CONFIGS_DIRECTORY);
            targetConfigsDirectory = DEFAULT_TARGET_CONFIGS_DIRECTORY;
        }
        configServerUri = getPropertyOrEnv(CONFIG_SERVER_URI);
        if (configServerUri != null) {
            logger.info("Loading configs from config server");
            if(logger.isDebugEnabled()) {
                logger.debug("light-env:" + lightEnv);
                logger.debug("targetConfigsDirectory:" + targetConfigsDirectory);
                logger.debug("configServerUri:" + configServerUri);
            }

            try {
                String configPath = getConfigServerPath();

                loadConfigs(configPath);

                loadFiles(configPath, CONFIG_SERVER_CERTS_CONTEXT_ROOT);

                loadFiles(configPath, CONFIG_SERVER_FILES_CONTEXT_ROOT);
            } catch (Exception e) {
                logger.error("Failed to connect to config server", e);
            }

            try {
                String filename = System.getProperty("logback.configurationFile");
                if (filename != null && Files.exists(Paths.get(filename))) {
                    // reset the default context (which may already have been initialized)
                    // since we want to reconfigure it
                    logger.info("Resetting logback configuration from {}", filename);
                    LoggerContext lc = (LoggerContext)LoggerFactory.getILoggerFactory();
                    lc.reset();
                    JoranConfigurator config = new JoranConfigurator();
                    config.setContext(lc);
                    config.doConfigure(filename);
                }
            } catch (JoranException e) {
                logger.error("Logback configuration failed", e);
            }
        } else {
            logger.warn("Warning! {} is not provided; using local configs", CONFIG_SERVER_URI);
        }
    }

    /**
     * load config properties from light config server
     * @param configPath
     */
    private void loadConfigs(String configPath) {
        //config Server Configs Path
        String configServerConfigsPath = CONFIG_SERVER_CONFIGS_CONTEXT_ROOT + configPath;
        //get service configs and put them in config cache
        Map<String, Object> serviceConfigs = getServiceConfigs(configServerConfigsPath);

        //set the environment value (the one used to fetch configs) in the serviceConfigs going into configCache
        serviceConfigs.put(ENV_PROPERTY_KEY, lightEnv);
        logger.debug("serviceConfigs received from Config Server: {}", serviceConfigs);

        // pass serviceConfigs through Config.yaml's load method so that it can decrypt any encrypted values
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);//to get yaml string without curly brackets and commas
        serviceConfigs = Config.getInstance().getYaml().load(new Yaml(options).dump(serviceConfigs));

        //clear config cache: this is required just in case other classes have already loaded something in cache
        Config.getInstance().clear();
        Config.getInstance().putInConfigCache(CENTRALIZED_MANAGEMENT, serviceConfigs);
        //You can call Server.getServerConfig() now.
    }

    /**
     * load config/cert files from light config server
     * @param configPath
     * @param contextRoot
     */
    private void loadFiles(String configPath, String contextRoot) {
        //config Server Files Path
        String configServerFilesPath = contextRoot + configPath;
        //get service files and put them in config dir
        Map<String, Object> serviceFiles = getServiceConfigs(configServerFilesPath);
        logger.debug("{} files loaded from config sever.", serviceFiles.size());
        logger.debug("loadFiles: {}", serviceFiles);
        try {
            Path filePath = Paths.get(targetConfigsDirectory);
            if (!Files.exists(filePath)) {
                Files.createDirectories(filePath);
                logger.info("target configs directory created :", targetConfigsDirectory);
            }
            Base64.Decoder decoder = Base64.getMimeDecoder();
            for (String fileName : serviceFiles.keySet()) {
                filePath=Paths.get(targetConfigsDirectory+"/"+fileName);
                byte[] ba = decoder.decode(serviceFiles.get(fileName).toString().getBytes());
                if(logger.isDebugEnabled()) logger.debug("filename = " + fileName + " content = " + new String(ba, StandardCharsets.UTF_8));
                Files.write(filePath, ba);
            }
        }  catch (IOException e) {
            logger.error("Exception while creating {} dir or creating files there:{}",targetConfigsDirectory, e);
        }
    }

    /**
     * This is a public method that is used to test the connectivity in the integration test to ensure that the
     * light-config-server can be connected with the default bootstrap.truststore. There is no real value for
     * this method other than that.
     * @param host config server host
     * @param path config server path
     * @return String of OK
     */
    public static String getConfigServerHealth(String host, String path) {
        String result = null;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + path))
                .build();
        try {
            HttpResponse<String> response = configClient.send(request, HttpResponse.BodyHandlers.ofString());
            result = response.body();
        } catch (Exception e) {
            logger.error("Exception while calling config server:", e);
        }
        return result;
    }

    private Map<String, Object> getServiceConfigs(String configServerPath) {
        String authorization = getPropertyOrEnv(AUTHORIZATION);
        if(authorization == null) authorization = ""; // give it an empty string to avoid NPE.

        Map<String, Object> configs = new HashMap<>();

        logger.debug("Calling Config Server endpoint:{}{}", configServerUri, configServerPath);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configServerUri + configServerPath))
                .header(Headers.AUTHORIZATION_STRING, authorization)
                .build();

        try {
            HttpResponse<String> response = configClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String body = response.body();
            if(statusCode >= 300) {
                logger.error("Failed to load configs from config server" + statusCode + ":" + body);
                throw new Exception("Failed to load configs from config server: " + statusCode);
            } else {
                Map<String, Object> responseMap = (Map<String, Object>) mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
                configs = (Map<String, Object>) responseMap.get("configProperties");
            }
        } catch (Exception e) {
            logger.error("Exception while calling config server:", e);
        }
        return configs;
    }

    private static String getConfigServerPath() {
        StringBuilder configPath = new StringBuilder();
        configPath.append("/").append(startupConfig.get(PROJECT_NAME));
        configPath.append("/").append(startupConfig.get(PROJECT_VERSION));
        configPath.append("/").append(startupConfig.get(SERVICE_NAME));
        configPath.append("/").append(startupConfig.get(SERVICE_VERSION));
        configPath.append("/").append(lightEnv);
        if(logger.isDebugEnabled()) logger.debug("configPath: {}", configPath);
        return configPath.toString();
    }

    private static KeyStore loadBootstrapTrustStore(){
        String truststorePassword = getPropertyOrEnv(CLIENT_TRUSTSTORE_PASS);
        String truststoreLocation = getPropertyOrEnv(CLIENT_TRUSTSTORE_LOC);
        if(truststoreLocation == null) truststoreLocation = Server.getServerConfig().getBootstrapStoreName();
        if(truststorePassword == null) truststorePassword = Server.getServerConfig().getBootstrapStorePass();

        try (InputStream stream = new FileInputStream(truststoreLocation)) {
            if (stream == null) {
                String message = "Unable to load truststore '" + truststoreLocation + "', please provide the correct truststore to enable TLS connection.";
                if (logger.isErrorEnabled()) {
                    logger.error(message);
                }
                throw new RuntimeException(message);
            }
            KeyStore loadedKeystore = KeyStore.getInstance("JKS");
            loadedKeystore.load(stream, truststorePassword != null ? truststorePassword.toCharArray() : null);
            return loadedKeystore;
        } catch (Exception e) {
            logger.error("Unable to load truststore: " + truststoreLocation, e);
            throw new RuntimeException("Unable to load truststore: " + truststoreLocation, e);
        }
    }

    private static TrustManager[] buildTrustManagers(final KeyStore trustStore) {
        TrustManager[] trustManagers = null;
        try {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory
                    .getInstance(KeyManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            trustManagers = trustManagerFactory.getTrustManagers();
        } catch (NoSuchAlgorithmException | KeyStoreException e) {
            logger.error("Unable to initialise TrustManager[]", e);
            throw new RuntimeException("Unable to initialise TrustManager[]", e);
        }
        return trustManagers;
    }


    private static SSLContext createBootstrapContext() throws RuntimeException {
        SSLContext sslContext = null;
        try {
            TrustManager[] trustManagers = buildTrustManagers(loadBootstrapTrustStore());
            sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, trustManagers, null);
        } catch (Exception e) {
            logger.error("Unable to create SSLContext", e);
            throw new RuntimeException("Unable to create SSLContext", e);
        }
        return sslContext;
    }

    private static String getPropertyOrEnv(String key) {
        // The key should be in lower case and separated with hyphen.
        // Always check the -D and then env variable for the key with lower and upper case.
        String s = System.getProperty(key);
        if(s == null) {
            s = System.getenv(key);
        }
        if(s == null) {
            s = System.getenv(key.toUpperCase());
        }
        if(s == null) {
            // Linux convention for the environment variables with underscore.
            s = System.getenv(key.toUpperCase().replaceAll("-", "_"));
        }
        return s;
    }
}
