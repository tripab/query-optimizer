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

## Milestone 3: Rule Engine & Optimization

### Overview

Milestone 3 implements the core optimization engine: rule-based transformations, cost modeling, and cardinality
estimation. This is where query plans get transformed into more efficient equivalent plans.

### Rule Engine with Fixpoint Iteration

**Architecture:**

```
RuleEngine
├── Applies rules repeatedly until no changes (fixpoint)
├── Bottom-up traversal (children optimized first)
├── Configurable iteration limit
└── Verbose mode for debugging
```

**Key Algorithm:**

```java
while(not fixpoint &&iterations<max){
        for
each rule:
apply rule
exhaustively to
entire tree
    if(
no changes):
        break // fixpoint reached
        }
```

**Usage:**

```java
List<Rule> rules = Arrays.asList(
        new PredicatePushdown(),
        new ProjectionPushdown(),
        new FilterMerge()
);

RuleEngine engine = new RuleEngine(rules, maxIterations);
LogicalNode optimizedPlan = engine.optimize(initialPlan);
```

### Optimization Rules

#### 1. Predicate Pushdown

**Pattern:** `Filter → Join`  
**Transform:** Push filter to appropriate join input

**Algorithm:**

1. Analyze which tables the predicate references
2. If predicate references only left table → push to left child
3. If predicate references only right table → push to right child
4. If predicate references both → keep above join

**Example:**

```
BEFORE:                          AFTER:
Filter[city='Seattle']           Join[c.id = o.customer_id]
└── Join[c.id = o.cust_id]      ├── Filter[city='Seattle']
    ├── Scan[customers]         │   └── Scan[customers]
    └── Scan[orders]            └── Scan[orders]
```

**Why it works:** For inner joins, filtering before or after produces same result, but filtering earlier reduces
intermediate result size.

#### 2. Projection Pushdown

**Pattern:** `Project → Filter`  
**Transform:** Swap to `Filter → Project`

**Safety Check:**

- Ensure filter predicate doesn't reference columns eliminated by projection
- Only swap if safe

**Example:**

```
BEFORE:                    AFTER:
Project[name]              Filter[price > 100]
└── Filter[price > 100]    └── Project[name]
    └── Scan[products]         └── Scan[products]
```

**Why it works:** Filters don't change schema, and filtering first reduces data volume.

#### 3. Filter Merge

**Pattern:** `Filter → Filter`  
**Transform:** Single filter with AND predicate

**Example:**

```
BEFORE:                          AFTER:
Filter[age > 30]                 Filter[(city='Seattle') AND (age > 30)]
└── Filter[city='Seattle']       └── Scan[customers]
    └── Scan[customers]
```

**Why it works:** Reduces operator overhead during execution. This reverses the canonical form after optimization is
complete.

### Cost Model

**Formula:**

```
Cost = I/O_cost + CPU_cost

I/O_cost  = pages_read * PAGE_COST
CPU_cost  = tuples_processed * TUPLE_COST

pages = ceil(rows / PAGE_SIZE)
```

**Configurable Parameters:**

```java
CostConfig {
    PAGE_COST = 1.0         // Cost to read one page
    TUPLE_COST = 0.01       // Cost to process one tuple
    PAGE_SIZE = 100         // Tuples per page
    COMPARISON_COST = 0.001 // Cost per comparison
    HASH_COST = 0.005       // Cost to hash one tuple
}
```

**Operator Costs:**

| Operator   | Formula                                                               |
|------------|-----------------------------------------------------------------------|
| Scan       | `pages(table) * PAGE_COST + rows * TUPLE_COST`                        |
| Filter     | `child_cost + rows * COMPARISON_COST`                                 |
| Project    | `child_cost + rows * TUPLE_COST`                                      |
| Join (NLJ) | `left_cost + right_cost + (left_rows * right_rows * COMPARISON_COST)` |
| Aggregate  | `child_cost + rows * HASH_COST`                                       |

**Usage:**

```java
CostModel costModel = new SimpleCostModel(catalog);

// Estimate cost
double cost = costModel.estimateCost(plan);

// Estimate cardinality
long rows = costModel.estimateCardinality(plan);

// Custom configuration
CostConfig config = new CostConfig(2.0, 0.02, 100);
CostModel customModel = new SimpleCostModel(catalog, config);
```

### Cardinality Estimation

**Techniques:**

**1. Scan Cardinality**

```
cardinality(Scan[T]) = |T|  (table row count)
```

**2. Filter Selectivity**

| Predicate Type    | Formula                                   |
|-------------------|-------------------------------------------|
| `col = value`     | `1 / NDV(col)`                            |
| `col != value`    | `1 - (1 / NDV(col))`                      |
| `col > value`     | `0.33` (heuristic)                        |
| `col < value`     | `0.33` (heuristic)                        |
| `pred1 AND pred2` | `sel(pred1) * sel(pred2)`                 |
| `pred1 OR pred2`  | `sel(pred1) + sel(pred2) - (sel1 * sel2)` |

```
cardinality(Filter) = input_rows * selectivity
```

**3. Join Cardinality**

For equality join `R.a = S.b`:

```
cardinality = (|R| * |S|) / max(NDV(a), NDV(b))
```

This assumes:

- Uniform distribution
- Independence
- Foreign key-like relationships

**4. Aggregate Cardinality**

```
cardinality(GroupBy[cols]) = min(input_rows, product of NDVs)
cardinality(no GROUP BY)   = 1
```

**Example:**

```java
CardinalityEstimator estimator = new CardinalityEstimator(catalog);

// Scan products (7 rows)
LogicalScan scan = new LogicalScan("products");
long scanCard = estimator.estimate(scan);  // 7

// Filter category='Electronics' (NDV=2, so selectivity=0.5)
Expression pred = ... // category = 'Electronics'
LogicalFilter filter = new LogicalFilter(pred, scan);
long filterCard = estimator.estimate(filter);  // ~3-4


##
Running the
Demo

###Build

```bash
git clone
https://github.com/tripab/query-optimizer
cd query-optimizer
mvn clean
install
```

### Run Demos

### Run the **FoundationDemo** class to see the Milestone 1 features in action.

**Expected Output:**

- Creates 3 sample CSV files (customers, products, orders)
- Loads tables into catalog
- Displays metadata and statistics
- Shows hardcoded logical plan with annotations
- Demonstrates core interfaces

### Run the **ParserAndLogicalPlansDemo** class to see the Milestone 2 features in action.

**Expected Output:**
Demonstrates 5 queries:

1. Simple filter and projection
2. Multiple filters (shows canonical form)
3. Join with filter
4. Multi-way join
5. Aggregation with GROUP BY

Each query shows: original SQL, parsed AST, logical plan tree and operator analysis

#### Example Queries

#### Query 1: Simple Filter

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

#### Query 2: Canonical Form Demo

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

#### Query 3: Join

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

#### Query 4: Aggregation

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

### Run the **RuleEngineAndOptimizationsDemo** class to see the Milestone 3 features in action.

**Expected Output:**
Demonstrates 3 queries:

1. Predicate pushdown with before/after comparison
2. Multiple predicate pushdown in multi-way join
3. Cost model sensitivity analysis

Each query shows:

- Initial unoptimized plan
- Optimized plan with costs
- Cost improvement percentage

#### Example: Complete Optimization

```sql
SELECT c.name, o.total
FROM customers c
         INNER JOIN orders o ON c.id = o.customer_id
WHERE c.city = 'Seattle'
  AND o.total > 100
```

**Initial Plan (Unoptimized):**

```
Project[name, total] [rows=?, cost=?]
└── Filter[(o.total > 100)]
    └── Filter[(c.city = 'Seattle')]
        └── Join[INNER, c.id = o.customer_id]
            ├── Scan[customers] [rows=8]
            └── Scan[orders] [rows=10]
```

**After Optimization:**

```
Project[name, total] [rows=2, cost=1.85]
└── Join[INNER, c.id = o.customer_id] [rows=2, cost=1.75]
    ├── Filter[(c.city = 'Seattle')] [rows=4, cost=0.12]
    │   └── Scan[customers] [rows=8, cost=0.11]
    └── Filter[(o.total > 100)] [rows=3, cost=0.14]
        └── Scan[orders] [rows=10, cost=0.13]
```

**Optimizations Applied:**

1. **Predicate Pushdown**: Both filters pushed below join
2. **Cost Reduction**: Intermediate result size reduced from 80 to 12 rows
3. **Improvement**: ~45% cost reduction

## Run Tests

These runs automatically when you build with Maven without skipping tests,

- FoundationTest contains end-to-end tests for Milestone 1 features
- ParsingAndLogicalPlansTest contains end-to-end tests for Milestone 2 features
- RuleEngineAndOptimizationsTest contains end-to-end tests for Milestone 3 features

## File Reference

| File                                                      | Purpose                                          |
|-----------------------------------------------------------|--------------------------------------------------|
| `org/query/optimizer/catalog/DataType.java`               | Supported data types (INTEGER, FLOAT, VARCHAR)   |
| `org/query/optimizer/catalog/Schema.java`                 | Table schema with column definitions             |
| `org/query/optimizer/catalog/ColumnStats.java`            | Simple column statistics (NDV, min/max, nulls)   |
| `org/query/optimizer/catalog/TableMetadata.java`          | Table metadata including schema, stats, and data |
| `org/query/optimizer/catalog/Catalog.java`                | Central metadata registry with CSV loading       |
| `org/query/optimizer/logical/LogicalNode.java`            | Base class for logical plan nodes                |
| `org/query/optimizer/logical/Expression.java`             | Expression trees (columns, literals, binary ops) |
| `org/query/optimizer/physical/PhysicalNode.java`          | Base class for physical plan nodes               |
| `org/query/optimizer/optimizer/Rule.java`                 | Interface for optimization rules                 |
| `org/query/optimizer/optimizer/CostModel.java`            | Interface for cost estimation with config        |
| `org/query/optimizer/executor/Iterator.java`              | Volcano-model execution interface                |
| `org/query/optimizer/FoundationDemo.java`                 | Demonstrates all Milestone 1 features            |
| `org/query/optimizer/FoundationTest.java`                 | End-to-end tests for Milestone 1                 |
| `org/query/optimizer/parser/AST.java`                     | Complete AST node hierarchy                      |
| `org/query/optimizer/parser/SQLParser.java`               | Hand-written SQL parser                          |
| `org/query/optimizer/parser/LogicalPlanBuilder.java`      | AST → Logical plan visitor                       |
| `org/query/optimizer/logical/LogicalScan.java`            | Table scan operator                              |
| `org/query/optimizer/logical/LogicalFilter.java`          | Filter/selection operator                        |
| `org/query/optimizer/logical/LogicalProject.java`         | Projection operator                              |
| `org/query/optimizer/logical/LogicalJoin.java`            | Join operator                                    |
| `org/query/optimizer/logical/LogicalAggregate.java`       | Aggregation operator                             |
| `org/query/optimizer/ParsingAndLogicalPlansDemo.java`     | Demonstrates all Milestone 2 features            |
| `org/query/optimizer/ParsingAndLogicalPlansTest.java`     | End-to-end tests for Milestone 2                 |
| `org/query/optimizer/RuleEngine.java`                     | Fixpoint iteration engine                        |
| `org/query/optimizer/rules/PredicatePushdown.java`        | Push filters below joins                         |
| `org/query/optimizer/rules/ProjectionPushdown.java`       | Push projections below filters                   |
| `org/query/optimizer/rules/FilterMerge.java`              | Merge consecutive filters                        |
| `org/query/optimizer/SimpleCostModel.java`                | Cost estimation implementation                   |
| `org/query/optimizer/CardinalityEstimator.java`           | Row count estimation                             |
| `org/query/optimizer/RuleEngineAndOptimizationsDemo.java` | Demonstrates all Milestone 3 features            |
| `org/query/optimizer/RuleEngineAndOptimizationsTest.java` | End-to-end tests for Milestone 3                 |

## What's Next?

**Milestone 4** will add Physical Execution
