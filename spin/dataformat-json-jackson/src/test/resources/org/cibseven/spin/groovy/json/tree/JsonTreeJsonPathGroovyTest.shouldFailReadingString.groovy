package org.cibseven.spin.groovy.json.tree

jsonNode = S(input, "application/json");

jsonNode.jsonPath('$.id').stringValue();