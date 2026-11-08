/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.spin.json.golden;

import java.util.List;
import java.util.Objects;

public class ShapesHolder {
  private List<Shape> shapes;

  public List<Shape> getShapes() { return shapes; }
  public void setShapes(List<Shape> shapes) { this.shapes = shapes; }

  @Override
  public boolean equals(Object o) { return o instanceof ShapesHolder h && Objects.equals(shapes, h.shapes); }

  @Override
  public int hashCode() { return Objects.hashCode(shapes); }

  @Override
  public String toString() { return "ShapesHolder" + shapes; }
}
