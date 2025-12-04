package org.cibseven.bpm.engine.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

public interface SetupRestService {
  String PATH = "/setup";

  @GET
  @Path("/status")
  boolean requiresSetup();
}
