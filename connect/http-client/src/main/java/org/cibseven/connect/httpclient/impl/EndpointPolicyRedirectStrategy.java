/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.connect.httpclient.impl;

import java.net.URI;

import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.cibseven.connect.impl.ConnectorEndpointPolicy;

/**
 * Default redirect handling that fails on targets the {@link ConnectorEndpointPolicy} refuses.
 */
public class EndpointPolicyRedirectStrategy extends DefaultRedirectStrategy {

  protected final String connectorId;

  public EndpointPolicyRedirectStrategy(String connectorId) {
    this.connectorId = connectorId;
  }

  @Override
  public URI getLocationURI(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException {
    URI location = super.getLocationURI(request, response, context);
    ConnectorEndpointPolicy.forConnector(connectorId).checkUri(location);
    return location;
  }

}
