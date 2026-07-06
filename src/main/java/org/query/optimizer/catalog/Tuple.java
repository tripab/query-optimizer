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

    /**
     * Adapts row maps to tuples whose attributes follow {@code schema}'s column
     * order. Row maps are HashMaps, so iterating their entry set would produce
     * an arbitrary (hash-dependent) attribute order; positional consumers such
     * as the projection operator rely on tuple order matching the schema.
     */
    public static java.util.Iterator<Tuple> convert(
            Schema schema, java.util.Iterator<Map<Schema.Column, Object>> mapIterator) {
        return new java.util.Iterator<>() {
            @Override
            public boolean hasNext() {
                return mapIterator.hasNext();
            }

            @Override
            public Tuple next() {
                Map<Schema.Column, Object> map = mapIterator.next();
                Tuple tuple = new Tuple();
                for (Schema.Column column : schema.getColumns()) {
                    tuple.add(new Attribute(column, map.get(column)));
                }
                return tuple;
            }
        };
    }
}