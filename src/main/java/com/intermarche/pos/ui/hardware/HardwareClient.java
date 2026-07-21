package com.intermarche.pos.ui.hardware;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@Path("/api/hardware")
@RegisterRestClient(configKey = "hardware-api")
public interface HardwareClient {

    @GET
    @Path("/weight")
    @Produces(MediaType.TEXT_PLAIN)
    String getWeight();

    @POST
    @Path("/display")
    @Consumes(MediaType.TEXT_PLAIN)
    void setDisplay(String text);

    @POST
    @Path("/drawer/open")
    void openDrawer();

    // NOUVEAU : Interroge l'état réel du matériel
    @GET
    @Path("/drawer/status")
    @Produces(MediaType.TEXT_PLAIN)
    String getDrawerStatus();

    @POST
    @Path("/printer/print")
    @Consumes(MediaType.TEXT_PLAIN)
    void printTicket(String content);

    @POST
    @Path("/printer/cut")
    void cutPaper();
}