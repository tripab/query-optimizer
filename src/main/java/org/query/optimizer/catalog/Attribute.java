package org.query.optimizer.catalog;

import java.util.AbstractMap;

// Attribute as typedef for Map.Entry<Schema.Column, Object>
public class Attribute extends AbstractMap.SimpleEntry<Schema.Column, Object> {
    public Attribute(Schema.Column key, Object value) {
        super(key, value);
    }
}
