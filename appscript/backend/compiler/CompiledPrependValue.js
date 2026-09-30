class CompiledPrependValue extends CompiledValueOperation {

    apply(oldValue) {

        return String(this.value) +
            String(oldValue ?? "");

    }

}