/**
 * Copyright 2019 Hewlett Packard Enterprise Development LP
 */

package com.nimblestorage.npm.agent.resource;

import java.text.MessageFormat;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;

import com.nimblestorage.npm.agent.resource.data.Agent;

/**
 * @author granganathan
 *
 */

@Path("/rest/v1/agent")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AgentResourceImpl implements AgentResource {
    private static final Logger logger = Logger.getLogger(AgentResourceImpl.class);
    private static final String AGENT_VERSION = "1.0";
    private static final String AGENT_DESCRIPTION = "SAP HANA Agent";

    @Context
    HttpServletRequest request;

    @Override
    @GET
    public Response geAgentInfo() {
        logger.debug(MessageFormat.format("Returning agent info description = {0}, version = {1}", AGENT_DESCRIPTION, AGENT_VERSION));
        return Response.ok(new Agent().withDescription(AGENT_DESCRIPTION).withVersion(AGENT_VERSION)).build();
    }
}
