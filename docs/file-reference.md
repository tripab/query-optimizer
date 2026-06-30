# File reference

A one-line description of every source file. See the [README](../README.md) for the high-level tour and
[milestones.md](milestones.md) for the build walkthrough.

| File                                                                     | Purpose                                              |
|--------------------------------------------------------------------------|------------------------------------------------------|
| `org/query/optimizer/catalog/DataType.java`                              | Supported data types (INTEGER, FLOAT, VARCHAR)       |
| `org/query/optimizer/catalog/Schema.java`                                | Table schema with column definitions                 |
| `org/query/optimizer/catalog/ColumnStats.java`                           | Simple column statistics (NDV, min/max, nulls)       |
| `org/query/optimizer/catalog/TableMetadata.java`                         | Table metadata including schema, stats, and data     |
| `org/query/optimizer/catalog/Catalog.java`                               | Central metadata registry with CSV loading           |
| `org/query/optimizer/logical/LogicalNode.java`                           | Base class for logical plan nodes                    |
| `org/query/optimizer/logical/Expression.java`                            | Expression trees (columns, literals, binary ops)     |
| `org/query/optimizer/physical/PhysicalNode.java`                         | Base class for physical plan nodes                   |
| `org/query/optimizer/Rule.java`                                          | Interface for optimization rules                     |
| `org/query/optimizer/catalog/CostModel.java`                            | Interface for cost estimation with config            |
| `org/query/optimizer/executor/Iterator.java`                             | Volcano-model execution interface                    |
| `org/query/optimizer/FoundationDemo.java`                                | Demonstrates all Milestone 1 features                |
| `org/query/optimizer/FoundationTest.java`                                | End-to-end tests for Milestone 1                     |
| `org/query/optimizer/parser/AST.java`                                    | Complete AST node hierarchy                          |
| `org/query/optimizer/parser/SQLParser.java`                              | Hand-written SQL parser                              |
| `org/query/optimizer/parser/LogicalPlanBuilder.java`                     | AST to logical plan visitor                          |
| `org/query/optimizer/logical/LogicalScan.java`                           | Table scan operator                                  |
| `org/query/optimizer/logical/LogicalFilter.java`                         | Filter/selection operator                            |
| `org/query/optimizer/logical/LogicalProject.java`                        | Projection operator                                  |
| `org/query/optimizer/logical/LogicalJoin.java`                           | Join operator                                        |
| `org/query/optimizer/logical/LogicalAggregate.java`                      | Aggregation operator                                 |
| `org/query/optimizer/ParsingAndLogicalPlansDemo.java`                    | Demonstrates all Milestone 2 features                |
| `org/query/optimizer/ParsingAndLogicalPlansTest.java`                    | End-to-end tests for Milestone 2                     |
| `org/query/optimizer/RuleEngine.java`                                    | Fixpoint iteration engine                            |
| `org/query/optimizer/rules/PredicatePushdown.java`                       | Push filters below joins                             |
| `org/query/optimizer/rules/ProjectionPushdown.java`                      | Push projections below filters                       |
| `org/query/optimizer/rules/FilterMerge.java`                             | Merge consecutive filters                            |
| `org/query/optimizer/SimpleCostModel.java`                               | Logical cost estimation (uses a CardinalityModel)    |
| `org/query/optimizer/CardinalityEstimator.java`                          | Heuristic cardinality + per-column NDV propagation   |
| `org/query/optimizer/SubtreeStatistics.java`                             | Derived subtree stats (rows + per-column NDV/min/max)|
| `org/query/optimizer/CardinalityModel.java`                              | Swappable cardinality-estimation interface           |
| `org/query/optimizer/HeuristicCardinalityModel.java`                     | Default CardinalityModel (wraps CardinalityEstimator)|
| `org/query/optimizer/CardinalityModelType.java`                          | HEURISTIC vs LEARNED selector for OptimizationOptions |
| `org/query/optimizer/PhysicalCostEstimator.java`                         | Physical, algorithm-aware operator costing           |
| `org/query/optimizer/QueryOptimizer.java`                                | Unified parse→rewrite→reorder→annotate→physical flow |
| `org/query/optimizer/OptimizationOptions.java`                           | Optimizer configuration (rules + join policies)      |
| `org/query/optimizer/JoinOrderPolicy.java`                               | PRESERVE_INPUT vs DP join ordering                   |
| `org/query/optimizer/JoinAlgorithmPolicy.java`                           | COST_BASED / FORCE_HASH / FORCE_NLJ                  |
| `org/query/optimizer/DPJoinOrderer.java`                                 | Dynamic-programming join ordering                    |
| `org/query/optimizer/JoinExtractor.java`                                 | Extracts reorderable join leaves + conditions        |
| `org/query/optimizer/TraditionalOptimizerDemo.java`                      | Full traditional-pipeline showcase (strong vs naive) |
| `org/query/optimizer/RuleEngineAndOptimizationsDemo.java`                | Demonstrates all Milestone 3 features                |
| `org/query/optimizer/RuleEngineAndOptimizationsTest.java`                | End-to-end tests for Milestone 3                     |
| `org/query/optimizer/physical/PhysicalScan.java`                         | Table scan operator                                  |
| `org/query/optimizer/physical/PhysicalFilter.java`                       | Filter operator                                      |
| `org/query/optimizer/physical/PhysicalProject.java`                      | Project operator                                     |
| `org/query/optimizer/physical/PhysicalNestedLoopJoin.java`               | Nested loop join                                     |
| `org/query/optimizer/physical/PhysicalHashJoin.java`                     | Hash join                                            |
| `org/query/optimizer/physical/PhysicalAggregate.java`                    | Blocking GROUP BY / aggregation (Volcano)            |
| `org/query/optimizer/physical/JoinAlgorithmCounts.java`                  | Tally of hash vs nested-loop joins in a plan         |
| `org/query/optimizer/physical/PhysicalPlanBuilder.java`                  | Logical→physical conversion (cost-based join policy) |
| `org/query/optimizer/executor/Executor.java`                             | Execution engine                                     |
| `org/query/optimizer/PhysicalExecutionDemo.java`                         | Complete demonstrations                              |
| `org/query/optimizer/PhysicalExecutionTest.java`                         | Automated tests                                      |
| `org/query/optimizer/vectorized/ColumnVector.java`                       | Typed columnar storage for a single column           |
| `org/query/optimizer/vectorized/ColumnBatch.java`                        | Batch of rows in columnar form with selection vector |
| `org/query/optimizer/vectorized/ColumnarTable.java`                      | Row-to-column pivot of TableMetadata (cached)        |
| `org/query/optimizer/vectorized/VectorizedOperator.java`                 | Batch-at-a-time operator interface (open/next/close) |
| `org/query/optimizer/vectorized/VectorizedExpressionEvaluator.java`      | Predicate evaluation over a batch; builds sel-vector |
| `org/query/optimizer/vectorized/VectorizedScan.java`                     | Scans a ColumnarTable and emits batches              |
| `org/query/optimizer/vectorized/VectorizedFilter.java`                   | Filter via selection vector, no data movement        |
| `org/query/optimizer/vectorized/VectorizedProject.java`                  | Column selection by swapping vector references       |
| `org/query/optimizer/vectorized/VectorizedHashJoin.java`                 | Two-phase hash join (build + probe)                  |
| `org/query/optimizer/vectorized/AggregateAccumulator.java`               | Per-group accumulators for COUNT/SUM/AVG/MIN/MAX     |
| `org/query/optimizer/vectorized/VectorizedAggregate.java`                | Blocking aggregation with accumulate/emit phases     |
| `org/query/optimizer/vectorized/VectorizedPlanBuilder.java`              | Logical plan to vectorized operator tree             |
| `org/query/optimizer/vectorized/VectorizedExecutor.java`                 | Drives vectorized tree; returns ExecutionResult      |
| `org/query/optimizer/learned/common/HintSet.java`                        | Immutable optimizer configuration with 6 presets     |
| `org/query/optimizer/learned/common/PlanVariantGenerator.java`           | Generate + deduplicate physical plan variants        |
| `org/query/optimizer/learned/common/PlanFeaturizer.java`                 | Convert plan tree to 52-dim feature vector           |
| `org/query/optimizer/learned/common/ExecutionFeedback.java`              | Training signal record with logicalCost()            |
| `org/query/optimizer/learned/common/WorkloadGenerator.java`              | Random SQL workload generator (5 query shapes)       |
| `org/query/optimizer/learned/common/DataGenerator.java`                  | Synthetic table generator (customers/orders/products)|
| `org/query/optimizer/learned/cardinality/CardinalityFeaturizer.java`     | Logical subplan → CE feature vector (incl. heuristic)|
| `org/query/optimizer/learned/cardinality/LearnedCardinalityModel.java`   | NN log-cardinality model with heuristic fallback     |
| `org/query/optimizer/learned/cardinality/CardinalityTrainingData.java`   | Labels subplans with exact executed cardinalities    |
| `org/query/optimizer/learned/cardinality/CardinalityModelTrainer.java`   | Trains the CE network (log-card regression)          |
| `org/query/optimizer/learned/cardinality/QErrorStats.java`               | Q-error metric + aggregate evaluation harness        |
| `org/query/optimizer/learned/nn/ActivationFunction.java`                 | ReLU and sigmoid with derivatives                    |
| `org/query/optimizer/learned/nn/LossFunction.java`                       | MSE and BCE loss functions                           |
| `org/query/optimizer/learned/nn/SimpleNeuralNetwork.java`                | Feedforward MLP with online SGD and save/load        |
| `org/query/optimizer/learned/bao/PlanValueModel.java`                    | Ensemble latency predictor with uncertainty          |
| `org/query/optimizer/learned/bao/ThompsonSampler.java`                   | Thompson Sampling over plan ensemble                 |
| `org/query/optimizer/learned/bao/BanditOptimizer.java`                   | Bao end-to-end optimizer loop                        |
| `org/query/optimizer/learned/bao/BaoDemo.java`                           | Bao demonstration with learning curve output         |
| `org/query/optimizer/learned/lero/PairwiseComparator.java`               | Siamese network for plan ranking                     |
| `org/query/optimizer/learned/lero/PlanExplorer.java`                     | Execute all variants and generate training pairs     |
| `org/query/optimizer/learned/lero/LeroOptimizer.java`                    | Lero end-to-end optimizer with warm-up/warm phases   |
| `org/query/optimizer/learned/lero/LeroDemo.java`                         | Lero demonstration with accuracy and ranking output  |
| `org/query/optimizer/learned/benchmark/LearnedOptimizerBenchmark.java`   | Four-strategy harness (DEFAULT/ORACLE/BAO/LERO)      |
| `org/query/optimizer/learned/benchmark/BaoVsLeroDemo.java`               | Seven-section head-to-head comparative report        |
| `org/query/optimizer/LearnedOptimizerTest.java`                          | Tests for all AI plan selection components (Phase 1-5)|
