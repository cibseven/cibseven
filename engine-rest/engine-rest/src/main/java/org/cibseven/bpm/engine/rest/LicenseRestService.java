package org.cibseven.bpm.engine.rest;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.cibseven.bpm.engine.rest.dto.license.LicenseKeyDto;

public interface LicenseRestService {

  public static final String PATH = "/license";

  @Consumes(MediaType.APPLICATION_JSON)
  @POST
  void setLicense(LicenseKeyDto licenseKey);

  @Produces(MediaType.APPLICATION_JSON)
  @GET
  @Path("/status")
  String getLicenseKey();
  
}