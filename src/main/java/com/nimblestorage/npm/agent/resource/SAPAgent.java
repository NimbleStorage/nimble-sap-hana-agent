/**
 * Copyright 2019 Hewlett Packard Enterprise Development LP
 */
package com.nimblestorage.npm.agent.resource;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

// NOTE: The environment variable SAP_JDBC_DRIVER must be set on the host machine
// in order for this to work.  It must point to the local instance of ngdbc.jar.

public class SAPAgent {
    private static final Logger logger = Logger.getLogger(SAPAgent.class);
    private Connection sapConnect = null;
    private static final String SAP_TYPE_NAME = "SAP";
    private static final String SAP_DRIVER_ENV_VAR = "SAP_JDBC_DRIVER";
    private static final String SAP_DRIVER_CLASS = "com.sap.db.jdbc.Driver";

    private static final String SNAPSHOT_PREP_COMMAND = "BACKUP DATA FOR FULL SYSTEM CREATE SNAPSHOT";
    private static final String SNAPSHOT_POST_COMMAND = "BACKUP DATA FOR FULL SYSTEM CLOSE SNAPSHOT BACKUP_ID";
    private static final String GET_BACKUP_ID_COMMAND = "SELECT BACKUP_ID FROM M_BACKUP_CATALOG WHERE STATE_NAME='prepared'";

    private String sapDbIp;
    private String sapDbInstance;
    private String sapAuthentication = null;

    public SAPAgent() {
        // Get configuration
        readConfig();
        // Verify that the $SAP_JDBC_DRIVER environment variable has been set
        if (System.getenv(SAP_DRIVER_ENV_VAR) == null) {
            throw new IllegalStateException(SAP_DRIVER_ENV_VAR + " is not set. It must contain the path to ngdbc.jar.");
        }
    }

    /**
     * Performs the SAP pre-snapshot task.
     *
     * @return backupId - String with the backup Id. null = error.
     * @throws SQLException
     */
    public String sapPreSnapshot() throws SQLException {
        String backupId = null;
        if (sapConnect != null) {
            try (Statement statement = sapConnect.createStatement()) {
                statement.executeUpdate(SNAPSHOT_PREP_COMMAND);
                ResultSet rs = statement.executeQuery(GET_BACKUP_ID_COMMAND);
                while (rs.next()) {
                    backupId = rs.getString("BACKUP_ID");
                    logger.info("sapPreSnapshot BACKUP_ID = " + backupId);
                }
                try {
                    logger.info("sapPreSnapshot: Sleeping for 60 seconds...");
                    Thread.sleep(TimeUnit.SECONDS.toMillis(60));
                } catch (InterruptedException e) {
                    logger.error("sapPreSnapshot " + e.getMessage());
                }
                sapConnect.commit();
                return backupId;
            } catch (SQLException e) {
                logger.error("sapPreSnapshot: Failed to prepare for snapshot", e);
                throw e;
            } finally {
                try {
                    sapConnect.rollback();
                } catch (SQLException e1) {
                    logger.error("sapPreSnapshot ERROR: rollback failed.", e1);
                }
            }
        } else {
            throw new IllegalStateException("sapConnect is null. SAP connection must be established first");
        }
    }

    /**
     * Performs the SAP post-snapshot task.
     *
     * @return retVal - Integer return code: -1 = no connection,      -2 = error,
     *                                        0 = success
     * @throws SQLException
     */
    public void sapPostSnapshot(Boolean backupSuccess, String backupId, String snapId) throws SQLException {
        if (sapConnect != null) {
            String postCmd = SNAPSHOT_POST_COMMAND + " " + backupId;
            if (backupSuccess) {
                postCmd = postCmd + " SUCCESSFUL '" + snapId + "'";
            } else {
                postCmd = postCmd + " UNSUCCESSFUL 'HANA BACKUP DEMO failed to create Nimble snapshot'";
            }
            logger.info("sapPostSnapshot Query: " + postCmd + "\n");
            try (Statement statement = sapConnect.createStatement()) {
                Integer result = statement.executeUpdate(postCmd);
                logger.info("sapPostSnapshot Result: " + result.toString());
                sapConnect.commit();
            } catch (SQLException e) {
                logger.error("sapPostSnapshot ERROR: ", e);
                throw e;
            } finally {
                try {
                    sapConnect.rollback();
                } catch (SQLException e1) {
                    logger.error("sapPostSnapshot ERROR: rollback failed", e1);
                }
            }
        } else {
            throw new IllegalStateException("sapConnect is null. SAP connection must be established first");
        }
    }

    /**
     * Tests the SAP database connection. Note that this ONLY for test
     * and does not handle the database connection rigorously.
     *
     * @return result (String)
     */
    public String sapTest() {
        String result = "";
        if (sapConnect == null) {
            result = "Connection is null.";
        } else {
            String testStr = "SELECT * FROM M_LANDSCAPE_HOST_CONFIGURATION";
            try {
                Statement statement = sapConnect.createStatement();
                ResultSet rs = statement.executeQuery(testStr);
                logger.info("\n\tSAP RESULTS:\n");
                while (rs.next()) {
                    String host = rs.getString("HOST");
                    String active = rs.getString("HOST_ACTIVE");
                    result += host + "=" + active + ", ";
                    logger.info("\t\t" + result + "\n");
                }
            } catch (SQLException e) {
                logger.error("SAP database connection test failed", e);
                result = "Caught SQL exception: " + e.getMessage();
            }
        }
        return result;
    }

    public Boolean sapAuthenticate(String authVal) {
        Boolean authorized = false;
        if (sapAuthentication != null && sapConnect != null) {
            if (authVal == sapAuthentication) {
                authorized = true;
            }
        }
        return authorized;
    }

    public boolean isConnected() {
        if (sapConnect == null) {
            return false;
        }
        return true;
    }

    public void connect(String dbUser, String dbPass, String encodedAuthStr) {
        if (sapConnect == null) {
            sapConnect = connectDb(SAP_TYPE_NAME, SAP_DRIVER_CLASS, sapDbIp, sapDbInstance, dbUser, dbPass);
            if (sapConnect != null) {
                sapAuthentication = encodedAuthStr;
            }
        }
    }

    /**
     * Closes the SAP database connection
     */
    public void disconnect() {
        if (sapConnect != null) {
            try {
                sapConnect.close();
                sapConnect = null;
            } catch (SQLException e) {
                logger.error("disconnect ERROR: connection close failed.", e);
            }
        }
    }

    /**
     * Establishes a connection with the SAP database.
     *
     * @return connection - The JDBC connection object.
     */
    private Connection connectDb(String dbType, String dbDriver, String dbIp, String dbName, String dbUser, String dbPass) {
        try {
            Class.forName(dbDriver);
        } catch (ClassNotFoundException e) {
            logger.error("connectDb: Where is your " + dbType + " JDBC Driver? Include in your library path!", e);
            return null;
        }
        Connection connection = null;
        try {
            String connectStr = "jdbc:" + dbType.toLowerCase() + "://" + dbIp + "/" + dbName;
            connection = DriverManager.getConnection(connectStr, dbUser, dbPass);
        } catch (SQLException e) {
            logger.error("connectDb - failure: SQLException! Check output console!", e);
            return null;
        }
        if (connection != null) {
            try {
                connection.setAutoCommit(false);
            } catch (SQLException e) {
                logger.error("connectDb - ERROR: failed to set autocommit to false!", e);
            }
        } else {
            logger.error("connectDb - failure: did not connect to " + dbType);
        }
        return connection;
    }

    private void readConfig() {
        // Load config file
        File fileConfig = null;
        List<String> configLocations = Arrays.asList("./sap-hana-backup-agent-config.xml");
        for (int i = 0; i < configLocations.size(); i++) {
            fileConfig = new File(configLocations.get(i));
            if (fileConfig.exists() && fileConfig.isFile()) {
                //doPrint("readConfig - found file: " + configLocations.get(i) + "\n");
                break;
            } else {
                fileConfig = null;
            }
        }
        // Read XML
        if (fileConfig != null) {
            try {
                DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                Document doc = dBuilder.parse(fileConfig);
                doc.getDocumentElement().normalize();
                NodeList nList = doc.getElementsByTagName("sap_hana_backup_agent");
                Node nNode = nList.item(0);
                if (nNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element eElement = (Element) nNode;
                    sapDbIp = eElement.getElementsByTagName("host_ip").item(0).getTextContent();
                    String port = eElement.getElementsByTagName("port").item(0).getTextContent();
                    sapDbIp = sapDbIp + ":" + port;
                    sapDbInstance = eElement.getElementsByTagName("instance").item(0).getTextContent();
                }
            } catch (Exception e) {
                logger.error("Failed to read config file", e);
            }
        } else {
            logger.error("Failed to find config file");
        }

    }
}
