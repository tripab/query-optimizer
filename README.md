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

## Milestone 2: Parsing & Logical Plans

### Overview

Milestone 2 implements SQL parsing, AST generation, and conversion to canonical logical plans. This establishes the
foundation for query optimization by creating a clean, normalized representation of queries.

### SQL Parser

**Supported SQL Syntax:**

```sql
SELECT column1, column2, aggregate(column)
FROM table1
         INNER JOIN table2 ON condition
    [
         INNER JOIN table3 ON condition]
WHERE condition
GROUP BY column1, column2
```

**Features:**

- Hand-written recursive descent parser
- Regex-based tokenization
- Support for qualified columns (table.column)
- Binary operators: =, !=, >, >=, <, <=
- Logical operators: AND, OR
- Aggregate functions: COUNT, SUM, AVG, MIN, MAX
- Literals: integers, floats, strings

**Explicitly NOT Supported** (phase 2):

- Subqueries, UNION, DISTINCT
- Outer joins (LEFT, RIGHT, FULL)
- Complex expressions (arithmetic, functions, CASE)
- ORDER BY, LIMIT, HAVING
- More than 3-way joins

### AST Classes

Complete Abstract Syntax Tree hierarchy in `AST.java`:

```java
SelectStmt
├──

SelectItem(abstract)
│   ├──ColumnSelectItem        // Regular columns
│   └──AggregateSelectItem     // COUNT(*), SUM(col), etc.
├──

FromClause(abstract)
│   ├──TableRef                // Single table
│   └──JoinClause              // INNER JOIN
└──

Expr(abstract)
    ├──ColumnExpr              // Column reference
    ├──LiteralExpr             // Constant value
    └──BinaryExpr              // Operators
```

**Usage Example:**

```java
SQLParser parser = new SQLParser();
AST.SelectStmt ast = parser.parse("SELECT name FROM products WHERE price > 100");
System.out.

println(ast); // Pretty-prints the AST
```

### Logical Operators

Five concrete logical operators representing relational algebra:

1. **LogicalScan** - Read from a table (leaf node)
2. **LogicalFilter** - Apply predicate (WHERE)
3. **LogicalProject** - Select columns (SELECT list)
4. **LogicalJoin** - Combine relations (JOIN)
5. **LogicalAggregate** - Group and aggregate (GROUP BY)

### LogicalPlanBuilder (Visitor Pattern)

Converts AST to logical plan with **canonical form enforcement**:

**Canonical Form Rules:**

1. **Split AND predicates** into separate Filter nodes
    - Input: `WHERE a = 1 AND b = 2 AND c = 3`
    - Output: 3 separate Filter nodes (one per predicate)
2. **Separate Filter nodes** from other operators
3. **Normalized expressions** (no redundancy)

**Why Canonical Form?**

- **Simplifies rules**: Each rule can assume a specific structure
- **Enables optimization**: Easier to move/reorder filters
- **Consistent representation**: Same query always produces same tree shape

**Example:**

```java
Catalog catalog = new Catalog();
catalog.

loadTableFromCSV("products","products.csv");

SQLParser parser = new SQLParser();
LogicalPlanBuilder builder = new LogicalPlanBuilder(catalog);

String sql = "SELECT name FROM products WHERE category = 'Electronics' AND price > 100";
AST.SelectStmt ast = parser.parse(sql);
LogicalNode plan = builder.build(ast);

// Plan structure (canonical form):
// Project[name]
// └── Filter[(price > 100)]
//     └── Filter[(category = 'Electronics')]
//         └── Scan[products]
```

## Running the Demo

### Build

```bash
git clone https://github.com/tripab/query-optimizer
cd query-optimizer
mvn clean install
```

### Run Demos

Run the FoundationDemo class to see the Milestone 1 features in action.

**Expected Output:**

- Creates 3 sample CSV files (customers, products, orders)
- Loads tables into catalog
- Displays metadata and statistics
- Shows hardcoded logical plan with annotations
- Demonstrates core interfaces

Run the ParserAndLogicalPlansDemo class to see the Milestone 2 features in action.

**Expected Output:**
Demonstrates 5 queries:

1. Simple filter and projection
2. Multiple filters (shows canonical form)
3. Join with filter
4. Multi-way join
5. Aggregation with GROUP BY

Each query shows: original SQL, parsed AST, logical plan tree and operator analysis

## Example Queries

### Query 1: Simple Filter

```sql
SELECT name, price
FROM products
WHERE category = 'Electronics'
```

**Logical Plan:**

```
└── Project[name, price]
    └── Filter[(category = 'Electronics')]
        └── Scan[products]
```

### Query 2: Canonical Form Demo

```sql
SELECT name
FROM customers
WHERE city = 'Seattle'
  AND age > 30
```

**Logical Plan (Note: 2 separate Filter nodes):**

```
└── Project[name]
    └── Filter[(age > 30)]
        └── Filter[(city = 'Seattle')]
            └── Scan[customers]
```

### Query 3: Join

```sql
SELECT c.name, o.total
FROM customers c
         INNER JOIN orders o ON c.id = o.customer_id
WHERE c.city = 'Seattle'
```

**Logical Plan:**

```
└── Project[name, total]
    └── Filter[(city = 'Seattle')]
        └── Join[INNER, (c.id = o.customer_id)]
            ├── Scan[customers]
            └── Scan[orders]
```

### Query 4: Aggregation

```sql
SELECT category, COUNT(*), AVG(price)
FROM products
GROUP BY category
```

**Logical Plan:**

```
└── Project[category, count_*, avg_price]
    └── Aggregate[GROUP BY: category; AGGS: COUNT(*) AS count_*, AVG(price) AS avg_price]
        └── Scan[products]
```

## Run Tests

These runs automatically when you build with Maven without skipping tests,

- FoundationTest contains end-to-end tests for Milestone 1 features
- ParsingAndLogicalPlansTest contains end-to-end tests for Milestone 2 features

## File Reference

| File                                                       | Purpose                                          |
|------------------------------------------------------------|--------------------------------------------------|
| `org/query/optimizer/catalog/DataType.java`                | Supported data types (INTEGER, FLOAT, VARCHAR)   |
| `org/query/optimizer/catalog/Schema.java`                  | Table schema with column definitions             |
| `org/query/optimizer/catalog/ColumnStats.java`             | Simple column statistics (NDV, min/max, nulls)   |
| `org/query/optimizer/catalog/TableMetadata.java`           | Table metadata including schema, stats, and data |
| `org/query/optimizer/catalog/Catalog.java`                 | Central metadata registry with CSV loading       |
| `org/query/optimizer/logical/LogicalNode.java`             | Base class for logical plan nodes                |
| `org/query/optimizer/logical/Expression.java`              | Expression trees (columns, literals, binary ops) |
| `org/query/optimizer/physical/PhysicalNode.java`           | Base class for physical plan nodes               |
| `org/query/optimizer/optimizer/Rule.java`                  | Interface for optimization rules                 |
| `org/query/optimizer/optimizer/CostModel.java`             | Interface for cost estimation with config        |
| `org/query/optimizer/executor/Iterator.java`               | Volcano-model execution interface                |
| `org/query/optimizer/FoundationDemo.java`                  | Demonstrates all Milestone 1 features            |
| `org/query/optimizer/FoundationTest.java`                  | End-to-end tests for Milestone 1                 |
| `org/query/optimizer/parser/AST.java`                      | Complete AST node hierarchy                      |
| `org/query/optimizer/parser/SQLParser.java`                | Hand-written SQL parser                          |
| `org/query/optimizer/parser/LogicalPlanBuilder.java`       | AST → Logical plan visitor                       |
| `org/query/optimizer/logical/LogicalScan.java`             | Table scan operator                              |
| `org/query/optimizer/logical/LogicalFilter.java`           | Filter/selection operator                        |
| `org/query/optimizer/logical/LogicalProject.java`          | Projection operator                              |
| `org/query/optimizer/logical/LogicalJoin.java`             | Join operator                                    |
| `org/query/optimizer/logical/LogicalAggregate.java`        | Aggregation operator                             |
| `org/query/optimizer/ParsingAndLogicalPlansDemo.java`      | Demonstrates all Milestone 2 features            |
| `org/query/optimizer/test/ParsingAndLogicalPlansTest.java` | End-to-end tests for Milestone 1                 |

## What's Next?

**Milestone 3** will add:

- Rule interface implementations
- RuleEngine with fixpoint iteration
- Core optimization rules:
    - Predicate pushdown
    - Projection pushdown
    - Simple join reordering
- Simple cost model
- Cardinality estimation
