package org.cibseven.spin.groovy.json.tree

node = S(input, "application/json")

property1 = node.prop("order")
property2 = node.prop("dueUntil")

value1 = property1.value()
value2 = property2.value()
