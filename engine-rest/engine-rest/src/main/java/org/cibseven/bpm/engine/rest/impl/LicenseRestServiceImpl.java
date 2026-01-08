package org.cibseven.bpm.engine.rest.impl;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.rest.LicenseRestService;
import org.cibseven.bpm.engine.rest.dto.license.LicenseKeyDto;

import com.fasterxml.jackson.databind.ObjectMapper;

public class LicenseRestServiceImpl  extends AbstractRestProcessEngineAware implements LicenseRestService {

  
  public LicenseRestServiceImpl(String engineName, ObjectMapper objectMapper) {
    super(engineName, objectMapper);
  }

  @Override
  public void setLicense(LicenseKeyDto licenseKey) {
      ProcessEngine engine = getProcessEngine();
      engine.getManagementService().setLicenseKey(licenseKey.getLicenseKey());
  }

  @Override
  public String getLicenseKey() {
      ProcessEngine engine = getProcessEngine();
    String key = engine.getManagementService().getLicenseKey();
    return key;
  }

}
