package org.query.optimizer.catalog;

import java.util.ArrayList;
import java.util.Map;

// Tuple as typedef for List<Attribute>
public class Tuple extends ArrayList<Attribute> {
    public Object find(Schema.Column key) {
        for (Attribute attr : this) {
            if (attr.getKey().equals(key)) {
                return attr.getValue();
            }
        }
        return null;
    }

    public static java.util.Iterator<Tuple> convert(
            java.util.Iterator<Map<Schema.Column, Object>> mapIterator) {
        return new java.util.Iterator<>() {
            @Override
            public boolean hasNext() {
                return mapIterator.hasNext();
            }

            @Override
            public Tuple next() {
                Map<Schema.Column, Object> map = mapIterator.next();
                Tuple tuple = new Tuple();
                for (Map.Entry<Schema.Column, Object> entry : map.entrySet()) {
                    tuple.add(new Attribute(entry.getKey(), entry.getValue()));
                }
                return tuple;
            }
        };
    }
}