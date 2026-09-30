class DataCell {

    constructor(value = null) {

        this.value = value;

        this.formula = null;

        this.note = null;

        this.background = null;

    }

    clone() {

        const cell = new DataCell(this.value);

        cell.formula = this.formula;
        cell.note = this.note;
        cell.background = this.background;

        return cell;

    }

}