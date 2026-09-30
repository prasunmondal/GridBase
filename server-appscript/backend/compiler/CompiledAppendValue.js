class CompiledAppendValue extends CompiledValueOperation {

    apply(oldValue) {

        return String(
            oldValue ?? ""
        ) + String(this.value);

    }
}