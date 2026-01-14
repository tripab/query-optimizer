# A query optimizer built from scratch in Java

This project aims to be at the level between a toy demo and full production-grade system, and the motivation is to
understand the internals of a typical query optimizer by implementing one.

I will be implementing this project in a phased manner, and will hopefully be able to add a few blog posts documenting
my experience.

## Milestone 1: Foundation & Catalog

### Overview

Milestone 1 establishes the clean architectural foundation for the query optimizer. It implements the catalog layer,
core interfaces, and supporting infrastructure.

### Catalog System

**Key Features:**

- Central metadata registry for all tables
- CSV file loading with automatic schema detection
- Statistics collection (NDV, min/max, null counts)
- In-memory table storage

**Usage Example:**

```java
Catalog catalog = new Catalog();
TableMetadata customers = catalog.loadTableFromCSV("customers", "customers.csv");

// Query metadata
long rowCount = customers.getRowCount();
Schema schema = customers.getSchema();
ColumnStats stats = customers.getColumnStats("city");

// Access data
Object[] row = customers.getRow(0);
Object value = customers.getValue(0, "name");
```

**CSV Format:**

```
id:INTEGER,name:VARCHAR,age:INTEGER
1,Alice,30
2,Bob,25
```

### Core Interfaces

#### LogicalNode

Base class for logical plan operators with:

- Child management (`getChildren()`, `withChildren()`)
- Annotation system for optimizer metadata
- Pretty printing with cost/cardinality display

#### PhysicalNode

Base class for physical operators with:

- Similar structure to LogicalNode
- `estimateCost()` method for operator costing

#### Rule

Interface for optimization transformations:

```java
interface Rule {
    String getName();

    boolean matches(LogicalNode node);

    LogicalNode apply(LogicalNode node);
}
```

#### CostModel

Interface for cost and cardinality estimation:

```java
interface CostModel {
    double estimateCost(LogicalNode node);

    long estimateCardinality(LogicalNode node);

    CostConfig getConfig();
}
```

Includes configurable `CostConfig` with tunable constants:

- `PAGE_COST` - Cost to read/write a page
- `TUPLE_COST` - Cost to process a tuple
- `PAGE_SIZE` - Tuples per page

#### Iterator

Volcano-model execution interface:

```java
interface Iterator {
    void open();        // Initialize

    Object[] next();    // Get next tuple

    void close();       // Clean up
}
```

### Expression System

Shared expression tree for predicates and projections:

**Supported Expressions:**

- `ColumnRef` - Column references (qualified or unqualified)
- `Literal` - Integer, float, and string literals
- `BinaryOp` - Comparison (=, !=, >, <, >=, <=) and logical (AND, OR) operators

**Example:**

```java
// Build: age > 25 AND name = 'Alice'
Expression expr = new BinaryOp(Operator.AND,
                new BinaryOp(Operator.GT, new ColumnRef("age"), new Literal(25)),
                new BinaryOp(Operator.EQ, new ColumnRef("name"), new Literal("Alice"))
        );

// Evaluate against a row
Object result = expr.evaluate(row, schema);

// Generate SQL string
String sql = expr.toSqlString();  // "(age > 25) AND (name = 'Alice')"
```

### Plan Annotations

Both logical and physical nodes support annotations for optimizer metadata:

```java
node.setEstimatedRows(1000);
node.

setEstimatedCost(25.5);
node.

setAnnotation("selectivity",0.1);

long rows = node.getEstimatedRows();
double cost = node.getEstimatedCost();
```

Pretty printing automatically displays annotations:

```
└── Scan[products] [rows=7, cost=0.07]
```

## Running the Demo

### Build

```bash
git clone https://github.com/tripab/query-optimizer
cd query-optimizer
mvn clean install
```

### Run Demo

Run the FoundationDemo class to see the Milestone 1 features in action.

**Expected Output:**

- Creates 3 sample CSV files (customers, products, orders)
- Loads tables into catalog
- Displays metadata and statistics
- Shows hardcoded logical plan with annotations
- Demonstrates core interfaces

### Run Tests

Run the FoundationTest class (runs automatically when you build with Maven without skipping tests) to see an end-to-end
test for Milestone 1 features.

## File Reference

| File                         | Purpose                                          |
|------------------------------|--------------------------------------------------|
| `catalog/DataType.java`      | Supported data types (INTEGER, FLOAT, VARCHAR)   |
| `catalog/Schema.java`        | Table schema with column definitions             |
| `catalog/ColumnStats.java`   | Simple column statistics (NDV, min/max, nulls)   |
| `catalog/TableMetadata.java` | Table metadata including schema, stats, and data |
| `catalog/Catalog.java`       | Central metadata registry with CSV loading       |
| `logical/LogicalNode.java`   | Base class for logical plan nodes                |
| `logical/Expression.java`    | Expression trees (columns, literals, binary ops) |
| `physical/PhysicalNode.java` | Base class for physical plan nodes               |
| `optimizer/Rule.java`        | Interface for optimization rules                 |
| `optimizer/CostModel.java`   | Interface for cost estimation with config        |
| `executor/Iterator.java`     | Volcano-model execution interface                |
| `Milestone1Demo.java`        | Demonstrates all Milestone 1 features            |
| `test/Milestone1Test.java`   | Automated tests for validation                   |

## What's Next?

**Milestone 2** will add:

- SQL parser (hand-written or ANTLR)
- AST classes
- Logical plan operators (Scan, Filter, Project, Join)
- AST → Logical plan conversion visitor
- Canonical logical plan forms