package org.cibseven.spin.groovy.json.tree

jsonNode = S(input, "application/json");

nodeList = jsonNode.jsonPath('$.customers').elementList();