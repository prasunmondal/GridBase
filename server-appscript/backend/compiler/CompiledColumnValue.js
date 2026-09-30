// class CompiledColumnValue {
//
//     constructor(columnIndex,
//                 columnName,
//                 value,
//                 operation) {
//
//         this.columnIndex = columnIndex;
//
//         this.columnName = columnName;
//
//         this.value = value;
//
//         this.operation =
//             operation ||
//             ValueOperation.SET;
//
//     }
//
//     apply(oldValue) {
//
//         switch (this.operation) {
//
//             case ValueOperation.SET:
//
//                 return this.value;
//
//             case ValueOperation.APPEND:
//
//                 return String(
//                     oldValue == null
//                         ? ""
//                         : oldValue
//                 ) + String(this.value);
//
//             case ValueOperation.PREPEND:
//
//                 return String(this.value) +
//                     String(
//                         oldValue == null
//                             ? ""
//                             : oldValue
//                     );
//
//             default:
//
//                 throw new Error(
//                     "Unsupported value operation: " +
//                     this.operation
//                 );
//
//         }
//
//     }
//
// }