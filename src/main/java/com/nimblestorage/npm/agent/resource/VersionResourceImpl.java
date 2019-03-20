/**
 * Copyright 2019 Hewlett Packard Enterprise Development LP
 */

package com.nimblestorage.npm.agent.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * @author granganathan
 *
 */
@Path("/rest/version")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class VersionResourceImpl implements VersionResource {

    @Override
    public Response getVersion() {
        return Response.ok("v1").build();
    }

}
