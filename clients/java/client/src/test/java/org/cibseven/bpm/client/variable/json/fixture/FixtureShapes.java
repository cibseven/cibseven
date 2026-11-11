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
package org.cibseven.bpm.client.variable.json.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Holder of annotation-driven polymorphic values ({@code @class} type ids).
 */
public class FixtureShapes {

  @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
  public interface Shape {
  }

  public static class Circle implements Shape {

    private double radius;

    public Circle() {
    }

    public Circle(double radius) {
      this.radius = radius;
    }

    public double getRadius() {
      return radius;
    }

    public void setRadius(double radius) {
      this.radius = radius;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Circle && Double.compare(radius, ((Circle) o).radius) == 0;
    }

    @Override
    public int hashCode() {
      return Double.hashCode(radius);
    }
  }

  public static class Square implements Shape {

    private int side;

    public Square() {
    }

    public Square(int side) {
      this.side = side;
    }

    public int getSide() {
      return side;
    }

    public void setSide(int side) {
      this.side = side;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Square && side == ((Square) o).side;
    }

    @Override
    public int hashCode() {
      return side;
    }
  }

  private List<Shape> shapes = new ArrayList<>();

  public List<Shape> getShapes() {
    return shapes;
  }

  public void setShapes(List<Shape> shapes) {
    this.shapes = shapes;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof FixtureShapes && Objects.equals(shapes, ((FixtureShapes) o).shapes);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(shapes);
  }

}
