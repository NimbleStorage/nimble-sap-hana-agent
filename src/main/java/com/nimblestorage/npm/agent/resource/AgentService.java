/**
 * Copyright 2018 Hewlett Packard Enterprise Development LP
 */
package com.nimblestorage.npm.agent.resource;

import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.apache.log4j.Logger;
import org.bouncycastle.asn1.x509.GeneralName;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ssl.SslConnector;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import com.google.common.collect.Lists;

/**
 * @author granganathan
 *
 */
public class AgentService {
    private static final Logger logger = Logger.getLogger(AgentService.class);

    private static final char[] storeKey = {0x4e, 0x69, 0x6d, 0x62, 0x6c, 0x65, 0x20, 0x42, 0x61, 0x63, 0x6b, 0x75, 0x70, 0x20, 0x41, 0x67,
            0x65, 0x6e, 0x74, 0x20, 0x52, 0x65, 0x66, 0x65, 0x72, 0x65, 0x6e, 0x63, 0x65, 0x20, 0x49, 0x6d,
            0x70, 0x6c, 0x65, 0x6d, 0x65, 0x6e, 0x74, 0x61, 0x74, 0x69, 0x6f, 0x6e};
    private static final String SERVER_KEY_STORE = "etc/server.ks";
    private static final String SERVER_CERT_ALIAS = "nimble_backup_agent";

    private static final String[] EXCLUDE_PROTOCOLS = { "SSLv3", "SSLv2Hello", "TLSv1" };
    private static final String[] INCLUDE_PROTOCOLS = { "TLSv1.1", "TLSv1.2" };
    private static final int port = 9000;

    private static Server jettyServer;

    public static void main(String[] args) {
        try {
            logger.info("Starting Agent");

            File directory = new File("etc");
            if (!directory.exists()) {
                directory.mkdirs();
            }

            InetAddress ip = InetAddress.getLocalHost();
            String hostIpAddress = ip.getHostAddress();
            logger.info("host ip address : " + hostIpAddress);

            if (!Files.exists(Paths.get(SERVER_KEY_STORE))) {
                List<GeneralName> sans = Lists.newArrayList();
                sans.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
                sans.add(new GeneralName(GeneralName.iPAddress, hostIpAddress));

                SslUtil.generateAndStoreKeyAndCertificate("localhost", sans, SERVER_CERT_ALIAS, SERVER_KEY_STORE, storeKey);
            }

            SslConnector sslConnector = new SslSocketConnector();
            sslConnector.setPort(port);
            sslConnector.getSslContextFactory().addExcludeProtocols(EXCLUDE_PROTOCOLS);
            sslConnector.getSslContextFactory().setIncludeProtocols(INCLUDE_PROTOCOLS);
            sslConnector.getSslContextFactory().setCertAlias(SERVER_CERT_ALIAS);
            sslConnector.getSslContextFactory().setKeyStorePath(SERVER_KEY_STORE);
            sslConnector.getSslContextFactory().setKeyStorePassword(new String(storeKey));
            sslConnector.getSslContextFactory().setKeyStoreType("JCEKS");

            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
            context.setContextPath("/");

            jettyServer = new Server(9000);
            jettyServer.setConnectors(new Connector[] { sslConnector });
            jettyServer.setHandler(context);

            ServletHolder jerseyServlet = context.addServlet(org.glassfish.jersey.servlet.ServletContainer.class, "/*");

            // Tells the Jersey Servlet which REST service/class to load.
            jerseyServlet.setInitParameter("jersey.config.server.provider.packages", "com.nimblestorage.npm.agent.resource");

            // Start the test REST server
            jettyServer.start();
            jettyServer.join();
        } catch (Exception e) {
            logger.error("Failed to start agent ", e);
        }
    }
}
