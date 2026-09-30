class ExecutionCompiler {

    constructor() {

        this.binaryPredicates = new Map([

            [EqualsPredicate, CompiledEqualsPredicate],
            [NotEqualsPredicate, CompiledNotEqualsPredicate],

            [GreaterThanPredicate, CompiledGreaterThanPredicate],
            [GreaterThanEqualsPredicate, CompiledGreaterThanEqualsPredicate],

            [LessThanPredicate, CompiledLessThanPredicate],
            [LessThanEqualsPredicate, CompiledLessThanEqualsPredicate],

            [ContainsPredicate, CompiledContainsPredicate],
            [StartsWithPredicate, CompiledStartsWithPredicate],
            [EndsWithPredicate, CompiledEndsWithPredicate]

        ]);

        this.unaryPredicates = new Map([

            [IsNullPredicate, CompiledIsNullPredicate],
            [IsNotNullPredicate, CompiledIsNotNullPredicate]

        ]);

        this.multiValuePredicates = new Map([

            [InPredicate, CompiledInPredicate],
            [BetweenPredicate, CompiledBetweenPredicate]

        ]);

        this.valueOperations = new Map([

            [ValueOperation.SET, CompiledSetValue],
            [ValueOperation.APPEND, CompiledAppendValue],
            [ValueOperation.PREPEND, CompiledPrependValue]

        ]);

    }

    compile(context,
            worksheetData,
            executionPlan) {

        const compiledPlan =
            new CompiledPlan();

        compiledPlan
            .setId(
                executionPlan.getId()
            );

        compiledPlan
            .setType(
                executionPlan.getType()
            );

        const schema =
            worksheetData.getSchema();

        this.compilePredicates(
            schema,
            executionPlan,
            compiledPlan
        );

        this.compileOrderBy(
            schema,
            executionPlan,
            compiledPlan
        );

        this.compileProjections(
            schema,
            executionPlan,
            compiledPlan
        );

        this.compileValues(
            schema,
            executionPlan,
            compiledPlan
        );

        this.compileRows(
            schema,
            executionPlan,
            compiledPlan
        );

        this.copyPaging(
            executionPlan,
            compiledPlan
        );

        context.getDebug().add(
            "Compiled Predicates",
            compiledPlan.getPredicates().length
        );

        context.getDebug().add(
            "Compiled Order By",
            compiledPlan.getOrderBy().length
        );

        return compiledPlan;

    }

    compilePredicates(schema,
                      executionPlan,
                      compiledPlan) {

        const predicates =
            executionPlan.getPredicates();

        if (!predicates) {
            return;
        }

        for (const predicate of predicates) {

            compiledPlan.addPredicate(

                this.compilePredicate(
                    schema,
                    predicate
                )

            );

        }

    }

    compilePredicate(schema,
                     predicate) {

        const columnIndex =
            this.requireColumn(
                schema,
                predicate.getColumnName()
            );

        //
        // Unary
        //
        if (predicate instanceof UnaryPredicate) {

            const compiled =
                this.unaryPredicates.get(
                    predicate.constructor
                );

            if (!compiled) {

                throw new Error(
                    "Unsupported unary predicate: " +
                    predicate.constructor.name
                );

            }

            return new compiled(
                columnIndex
            );

        }

        //
        // Binary
        //
        if (predicate instanceof BinaryPredicate) {

            const compiled =
                this.binaryPredicates.get(
                    predicate.constructor
                );

            if (!compiled) {

                throw new Error(
                    "Unsupported binary predicate: " +
                    predicate.constructor.name
                );

            }

            return new compiled(
                columnIndex,
                predicate.getExpectedValue()
            );

        }

        //
        // Multi value
        //
        if (predicate instanceof MultiValuePredicate) {

            const compiled =
                this.multiValuePredicates.get(
                    predicate.constructor
                );

            if (!compiled) {

                throw new Error(
                    "Unsupported multi value predicate: " +
                    predicate.constructor.name
                );

            }

            if (predicate instanceof InPredicate) {

                return new compiled(
                    columnIndex,
                    predicate.getValues()
                );

            }

            if (predicate instanceof BetweenPredicate) {

                return new compiled(
                    columnIndex,
                    predicate.getMinimumValue(),
                    predicate.getMaximumValue()
                );

            }

        }

        throw new Error(
            "Unsupported predicate: " +
            predicate.constructor.name
        );

    }

    compileOrderBy(schema,
                   executionPlan,
                   compiledPlan) {

        const orderBy =
            executionPlan.getOrderBy();

        if (!orderBy ||
            orderBy.length === 0) {

            return;

        }

        for (const item of orderBy) {

            compiledPlan.addOrderBy(

                new CompiledOrderBy(

                    this.requireColumn(
                        schema,
                        item.getColumnName()
                    ),

                    item.getDirection()

                )

            );

        }

    }

    compileProjections(schema,
                       executionPlan,
                       compiledPlan) {

        const projections =
            executionPlan.getProjections();

        if (!projections ||
            projections.length === 0) {

            return;

        }

        for (const projection of projections) {

            compiledPlan.addProjection(

                new CompiledProjection(

                    this.requireColumn(
                        schema,
                        projection.getColumnName()
                    ),

                    projection.getColumnName()

                )

            );

        }

    }

    compileValues(schema,
                  executionPlan,
                  compiledPlan) {

        const values =
            executionPlan.getValues();

        if (!values ||
            values.length === 0) {

            return;

        }

        for (const value of values) {

            compiledPlan.addValue(

                this.compileValue(
                    schema,
                    value
                )

            );

        }

    }

    compileValue(schema,
                 value) {

        const compiledType =
            this.valueOperations.get(
                value.getOperation()
            );

        if (!compiledType) {

            throw new Error(
                "Unsupported value operation: " +
                value.getOperation()
            );

        }

        return new compiledType(

            this.requireColumn(
                schema,
                value.getColumnName()
            ),

            value.getColumnName(),

            value.getValue()

        );

    }

    compileRows(schema,
                executionPlan,
                compiledPlan) {

        const rows =
            executionPlan.getRows();

        if (!rows ||
            rows.length === 0) {

            return;

        }

        for (const row of rows) {

            const compiledRow =
                new CompiledInsertRow();

            for (const value of row.getValues()) {

                compiledRow.addValue(

                    this.compileValue(
                        schema,
                        value
                    )

                );

            }

            compiledPlan.addRow(
                compiledRow
            );

        }

    }

    copyPaging(executionPlan,
               compiledPlan) {

        compiledPlan.setLimit(
            executionPlan.getLimit()
        );

        compiledPlan.setOffset(
            executionPlan.getOffset()
        );

    }

    requireColumn(schema,
                  columnName) {

        const columnIndex =
            schema.getColumnIndex(
                columnName
            );

        if (columnIndex < 0) {

            throw new Error(
                "Unknown column: " +
                columnName
            );

        }

        return columnIndex;

    }

}