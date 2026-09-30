class Stopwatch {

    static measure(action) {

        const started = Date.now();

        const result = action();

        return {
            result: result,
            elapsed: Date.now() - started
        };

    }

}