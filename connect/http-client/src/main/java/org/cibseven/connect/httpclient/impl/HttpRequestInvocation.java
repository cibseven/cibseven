/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cibseven.connect.httpclient.impl;

import java.util.List;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.cibseven.connect.impl.AbstractRequestInvocation;
import org.cibseven.connect.impl.ConnectorEndpointPolicy;
import org.cibseven.connect.spi.ConnectorRequest;
import org.cibseven.connect.spi.ConnectorRequestInterceptor;

public class HttpRequestInvocation extends AbstractRequestInvocation<BasicClassicHttpRequest> {

  protected HttpClient client;

  /** {@code null}: no endpoint check. */
  protected String connectorId;

  public HttpRequestInvocation(BasicClassicHttpRequest target, ConnectorRequest<?> request, List<ConnectorRequestInterceptor> interceptorChain, HttpClient client) {
    this(target, request, interceptorChain, client, null);
  }

  public HttpRequestInvocation(BasicClassicHttpRequest target, ConnectorRequest<?> request, List<ConnectorRequestInterceptor> interceptorChain, HttpClient client, String connectorId) {
    super(target, request, interceptorChain);
    this.client = client;
    this.connectorId = connectorId;
  }

  public Object invokeTarget() throws Exception {
    // after the interceptors, which may change the target
    if (connectorId != null) {
      ConnectorEndpointPolicy.forConnector(connectorId).checkUri(target.getUri());
    }
    // execute the request
    return client.execute(target);
  }

}
