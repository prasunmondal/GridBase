class DebugContext {

    constructor() {
        this.entries = [];
    }

    add(key, value) {
        this.entries.push({
            key: key,
            value: value
        });
    }

    addMessage(message) {
        this.add("Message", message);
    }

    getEntries() {
        return this.entries;
    }

}