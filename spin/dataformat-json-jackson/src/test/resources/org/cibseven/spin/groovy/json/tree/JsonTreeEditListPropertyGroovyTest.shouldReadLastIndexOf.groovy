package org.cibseven.spin.groovy.json.tree

node = S(input, "application/json");

currencies = node.prop("orderDetails").prop("currencies");

value = currencies.lastIndexOf("dollar");