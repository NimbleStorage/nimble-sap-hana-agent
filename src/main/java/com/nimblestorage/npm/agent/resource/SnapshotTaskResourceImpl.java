/**
 * Copyright 2018 Hewlett Packard Enterprise Development LP
 */

package com.nimblestorage.npm.agent.resource;

import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.nimblestorage.npm.agent.resource.data.SnapshotTask;
import com.nimblestorage.npm.agent.resource.data.SnapshotTaskStatus;

@Path("/rest/v1/snapshot-tasks")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SnapshotTaskResourceImpl implements SnapshotTaskResource {
    private static final Logger logger = Logger.getLogger(SnapshotTaskResourceImpl.class);
    private static final int TIMEOUT_SECS = 600;
    private static final String AUTH_HEADER = "Authorization";
    private static final Map<String, SnapshotTask> tasks = Maps.newHashMap(); //taskId, snapshotTask
    private static final Map<String, String> snapshotToBackupIdMap = Maps.newHashMap(); //snapName, backupId
    private SAPAgent sapConnection = new SAPAgent();
    private static ExecutorService executor = Executors.newCachedThreadPool();

    @Context
    HttpServletRequest request;

    /**
     * Creates the task that executes the steps involved in placing the
     * application into a consistent state.
     *
     * @param SnapshotTask - request object for the task
     * @return Response - JAX-RS Response object indicating if the request was successfully accepted or not
     */
    @Override
    @Path("preSnapshotTask")
    @POST
    public Response preSnapshotTask(final SnapshotTask snapTask) {
        logger.info("preSnapshotTask - STARTING: received request");

        if (!authenticate()) {
            logger.error("Authentication failed");
            return Response.status(Status.UNAUTHORIZED).build();
        }

        if (snapTask == null) {
            logger.info("preSnapshotTask - error: bad request");
            return Response.status(Status.BAD_REQUEST).build();
        }

        // Create and start the task
        addTask(snapTask);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                startPreSnapTask(snapTask);
            }
        });

        // Response
        return Response.ok(snapTask).build();
    }


    /**
     * Creates the task that executes the steps involved in removing the
     * application from a consistent state.
     *
     * @param SnapshotTask - request object for the task
     * @return Response - JAX-RS Response object indicating if the request was successfully accepted or not
     */
    @Override
    @Path("postSnapshotTask")
    @POST
    public Response postSnapshotTask(SnapshotTask snapTask) {
        logger.info("postSnapshotTask - STARTING: received request");

        if (!authenticate()) {
            logger.info("postSnapshotTask - error: authentication failed");
            return Response.status(Status.UNAUTHORIZED).build();
        }

        if (snapTask == null) {
            logger.info("postSnapshotTask - error: bad request");
            return Response.status(Status.BAD_REQUEST).build();
        }

        // Create and start the task
        addTask(snapTask);
        startPostSnapTask(snapTask);

        // Response
        return Response.ok(snapTask).build();
    }


    /**
     * Returns the status of all snapshot tasks
     *
     * @return Response - JAX-RS Response object with a list of all snapshot tasks
     */
    @Override
    @GET
    public Response getStatus() {
        logger.info("getStatus - STARTING: getting status for all tasks");
        List<SnapshotTask> tasksList = Lists.newArrayList();
        for (String id : tasks.keySet()) {
            tasksList.add(tasks.get(id));
        }
        return Response.ok(tasksList).build();
    }

    /**
     * Returns the status of the snapshot task specified
     *
     * @param snapshotTaskId - id of the snapshot task
     * @return JAX-RS Response object with the snapshot task matching the id specified
     */
    @Override
    @Path("{snapshotTaskId}")
    @GET
    public Response getStatus(@PathParam("snapshotTaskId")String snapshotTaskId) {
        logger.info("getStatus - STARTING: task = " + snapshotTaskId);

        if (!authenticate()) {
            return Response.status(Status.UNAUTHORIZED).build();
        }

        if (snapshotTaskId == null) {
            return Response.status(Status.BAD_REQUEST).build();
        }

        // Return the status of the snapshot task with id equal to snapshotTaskId
        SnapshotTask task = tasks.get(snapshotTaskId);
        if (task != null) {
            // Response
            return Response.ok(task).build();
        }

        return Response.status(Status.NOT_FOUND).build();
    }

    /**
     * Delete the snapshot task specified so it no longer be tracked.
     *
     * @param snapshotTaskId - id of the snapshot task
     * @return Response - JAX-RS Response object with the status
     */
    @Override
    @Path("{snapshotTaskId}")
    @DELETE
    public Response deleteSnapshotTask(@PathParam("snapshotTaskId")String snapshotTaskId) {
        logger.info("deleteSnapshotTask - start: task = " + snapshotTaskId);
        if (!authenticate()) {
            return Response.status(Status.UNAUTHORIZED).build();
        }

        if (snapshotTaskId == null) {
            return Response.status(Status.BAD_REQUEST).build();
        }

        // Delete the snapshot task with id equal to snapshotTaskId
        if (tasks.get(snapshotTaskId) != null) {
            tasks.remove(snapshotTaskId);
            // Response
            return Response.ok().build();
        }
        logger.info("deleteSnapshotTask - failure: task = " + snapshotTaskId);
        // Response
        return Response.status(Status.NOT_FOUND).build();
    }

    /**
     * Assign a unique id to the snapshot task,
     * set timeout value and the status
     *
     * @param snapTask
     */
    private void addTask(SnapshotTask snapTask) {
        snapTask.setId(UUID.randomUUID().toString());
        snapTask.setTimeout(TIMEOUT_SECS);
        snapTask.setStatus(SnapshotTaskStatus.ACTIVE);

        // Example of preparing any metadata pertaining to the backup here to add to the snapshot of the volumes
        /*
        for (Volume vol : snapTask.getVolumes()) {
            List<KeyValue> metadata = Lists.newArrayList();
            metadata.add(new KeyValue("", ""));
            vol.setMetadata(metadata);
        }*/

        tasks.put(snapTask.getId(), snapTask);
    }

    /**
     * @param snapTask
     */
    private void startPreSnapTask(SnapshotTask snapTask) {
        // Start the snapshot task operation in the background
        logger.info("startPreSnapTask - STARTING");
        checkForFailedTasks(10);
        try {
            String backupId = sapConnection.sapPreSnapshot();
            Long timeStamp = System.currentTimeMillis() / 1000L;
            snapshotToBackupIdMap.put(snapTask.getSnapshotName(), backupId + "," + timeStamp.toString());
            logger.info("startPreSnapTask - snaps: " + snapshotToBackupIdMap.toString());
            snapTask.setStatus(SnapshotTaskStatus.SUCCESS);
        } catch (Exception e) {
            snapTask.setStatus(SnapshotTaskStatus.FAILED);
            snapTask.setMessage("Failed to execute command to prepare for SAN HANA backup");
        }

        sapConnection.disconnect();
    }

    /**
     * @param snapTask
     */
    private void startPostSnapTask(SnapshotTask snapTask) {
        // Start the snapshot task operation in the background
        String snapName = snapTask.getSnapshotName();
        String backupIdWithTimeStamp = snapshotToBackupIdMap.get(snapName);

        if (backupIdWithTimeStamp != null) {
            String backupId = backupIdWithTimeStamp.split(",")[0];
            logger.info(MessageFormat.format("startPostSnapTask - STARTING: backupId = {0} , snapName = {1}", backupId, snapName));
            logger.info("startPostSnapTask - snaps: " + snapshotToBackupIdMap.toString());

            if (backupId != null) {
                try {
                    sapConnection.sapPostSnapshot(true, backupId, snapName);
                    snapTask.setStatus(SnapshotTaskStatus.SUCCESS);
                } catch (SQLException e) {
                    logger.error("startPostSnapTask - failure: backupId = " + backupId + ", snapName = " + snapName);
                    snapTask.setStatus(SnapshotTaskStatus.FAILED);
                }
                snapshotToBackupIdMap.remove(snapName);
                sapConnection.disconnect();
            } else {
                snapTask.setStatus(SnapshotTaskStatus.SUCCESS);
            }
        } else {
            logger.info(MessageFormat.format("No backupId found for snapshot {0}", snapName));
        }
    }

    private void checkForFailedTasks(int timeoutSec) {
        // Iterate over the list of snaps
        logger.info("checkForFailedTasks - STARTING");
        for (Map.Entry<String, String> entry : snapshotToBackupIdMap.entrySet()) {
            String snapName = entry.getKey();
            String val = entry.getValue();
            String backupId = val.split(",")[0];
            Long timeStamp = Long.parseLong(val.split(",")[1]);
            Long delta = (System.currentTimeMillis() / 1000L) - timeStamp;
            if (delta > timeoutSec) {
                // If any snap's time stamp exceeds the timeout, close it as a failure
                logger.info("checkForFailedTasks - found:   backupId = " + backupId + ", snapName = " + snapName);
                try {
                    sapConnection.sapPostSnapshot(false, backupId, snapName);
                } catch (SQLException e) {
                    logger.error("checkForFailedTasks - ERROR:   backupId = " + backupId + ", snapName = " + snapName + ". Failed to close failed task in HANA.");
                }
                snapshotToBackupIdMap.remove(snapName);
                // Check the task list for the failed snap and remove it as well
                for (Map.Entry<String, SnapshotTask> task : tasks.entrySet()) {
                    String taskId = task.getKey();
                    SnapshotTask curTask = task.getValue();
                    String taskSnapName = curTask.getSnapshotName();
                    if (taskSnapName.equals(snapName)) {
                        tasks.remove(taskId);
                        logger.info("checkForFailedTasks - removed: backupId = " + backupId + ", snapName = " + snapName + ", taskId = " + taskId);
                        break;
                    }
                }
            }
        }
    }


    /**
     * With HTTP Basic Authentication,
     * the client's username and password are concatenated,
     * base64-encoded, and passed in the Authorization HTTP header as follows :
     *
     *        Authorization: Basic dm9yZGVsOnZvcmRlbA==
     *
     * @return true if credentials are valid
     */
    private boolean authenticate() {
        boolean isValidUser = false;
        String username = null;
        String password = null;
        String authHeaderValue = request.getHeader(AUTH_HEADER);
        if (!Strings.isNullOrEmpty(authHeaderValue)) {
            String segments[] = authHeaderValue.split(" ");
            String base64Creds = (segments.length == 2) ? segments[1] : "";
            String credentials[] = new String(Base64.decodeBase64(base64Creds)).split(":");
            if (credentials.length == 2) {
                username = credentials[0];
                password = credentials[1];
            }
        }

        logger.info("Authenticating credentials for user " + username);
        // Perform authentication
        if (!sapConnection.isConnected()) {
            sapConnection.connect(username, password, authHeaderValue);
        }
        isValidUser = sapConnection.sapAuthenticate(authHeaderValue);

        if (!isValidUser) {
            logger.error("authentication failed");
        }
        return isValidUser;
    }
}
