import tools.jackson.databind.type.TypeFactory

desiredType = TypeFactory.createDefaultInstance().constructCollectionType(collectionType, mapToType)

result = S(input, "application/json").mapTo(desiredType.toCanonical())

