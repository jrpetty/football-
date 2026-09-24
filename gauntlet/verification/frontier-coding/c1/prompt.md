Write a JavaScript function `runProgram(source, input, maxSteps)` that assembles and runs a program for the small register machine described below and reports what happened.

**Machine state**
- Eight registers `R0` to `R7`. Every register holds a 32-bit signed integer (from -2147483648 to 2147483647); all start at 0.
- Four flags `Z`, `N`, `C`, `V`, each true or false; all start false.
- A memory of 1024 words with addresses 0 to 1023; every word holds a 32-bit signed integer and starts at 0.
- One stack holding at most 256 values; it starts empty. `PUSH`, `POP`, `CALL` and `RET` all use this same stack.
- An instruction pointer (IP), starting at instruction 0, and an input queue holding the numbers of `input` in order.

Definitions: *wrapping* an exact integer x means taking the unique y with -2^31 <= y <= 2^31 - 1 and y ≡ x (mod 2^32). The *unsigned value* of a register value a is a if a >= 0 and a + 2^32 otherwise. "In range" means between -2^31 and 2^31 - 1 inclusive. Bit 0 is the least significant bit.

**Source format**
`source` is a string of lines separated by `\n`. On each line, everything from the first `;` to the end of the line is a comment and is ignored. What remains consists of zero or more label definitions followed by at most one instruction.
- A label definition is a name followed by `:` (spaces or tabs may appear before the name and between the name and the colon). A name is a letter or `_` followed by any number of letters, digits and `_`. Label names are case-sensitive (`loop` and `Loop` are different labels) and a name that is a register name (`R0` to `R7` in any letter case) may not be defined as a label.
- An instruction is a mnemonic (letters only), then, if it has operands, at least one space or tab followed by its operands separated by commas. Spaces and tabs around operands and commas are ignored. Mnemonics and register names are case-insensitive (`mov r1, 5` is the same as `MOV R1, 5`).
- Instructions are numbered 0, 1, 2, ... in order of appearance (lines without an instruction get no number). A label refers to the first instruction that comes after its definition (on the same line or a later line); a label with no instruction after it refers to the position just past the last instruction.

Operands:
- `r`: a register `R0` to `R7`.
- `v`: a register, or an immediate: an optional `+` or `-` followed by either one or more decimal digits or `0x`/`0X` and one or more hexadecimal digits (either letter case), with no spaces inside. The immediate's value is its exact value wrapped, e.g. `0xFFFFFFFF` is -1, `-0x10` is -16, `4294967297` is 1.
- `m`: a memory reference in square brackets, with optional spaces or tabs just inside the brackets and around `+`/`-`: `[Rk]` (address = value of Rk), `[Rk+d]` or `[Rk-d]` where d is one or more decimal digits (address = value of Rk plus or minus d, computed exactly, not wrapped), or `[imm]` where imm is an immediate as above (address = its wrapped value).
- `L`: a label name.

Instructions and their operands:
`MOV r,v` · `LOAD r,m` · `STORE m,v` · `ADD r,v` · `SUB r,v` · `MUL r,v` · `DIV r,v` · `MOD r,v` · `AND r,v` · `OR r,v` · `XOR r,v` · `SHL r,v` · `SHR r,v` · `SAR r,v` · `NEG r` · `NOT r` · `CMP r,v` · `JMP L` · `JE L` · `JNE L` · `JL L` · `JLE L` · `JG L` · `JGE L` · `JB L` · `JBE L` · `JA L` · `JAE L` · `LOOP r,L` · `CALL L` · `RET` · `PUSH v` · `POP r` · `IN r` · `OUT v` · `HALT`

If the source is malformed in any way (unknown mnemonic, wrong number or kind of operands, an operand that does not match its syntax above, a label defined twice, a register name used as a label definition, or a label operand naming a label that is never defined), nothing is executed and the result is `{status: "SYNTAX", output: [], steps: 0, registers: [0, 0, 0, 0, 0, 0, 0, 0]}`.

**Execution**
Let n be the number of instructions and `steps` the number of instructions executed so far (initially 0). Repeat:
1. If IP = n, stop with status `"HALT"`.
2. Otherwise, if `steps` = `maxSteps`, stop with status `"LIMIT"`.
3. Otherwise execute the instruction at IP. Unless it jumps, IP then advances by 1. If it completes normally, `steps` increases by 1 (this includes `HALT`, which then stops with status `"HALT"`). If it fails with one of the errors listed below, execution stops with that error as the status, the failing instruction has no effect at all, and it is not counted in `steps`.

In the table, a is the current value of the first operand's register and b the value of the second operand (register or immediate). Flags are only changed by the instructions that say so; "Z, N from the result" means Z = (result = 0) and N = (result < 0).
- `MOV r,v`: r = b.
- `LOAD r,m`: r = memory[address]. `STORE m,v`: memory[address] = value of v. If the address is not between 0 and 1023, error `"BAD_ADDRESS"`.
- `ADD r,v`: s = a + b exactly; r = wrap(s); Z, N from the result; C = (unsigned a + unsigned b >= 2^32); V = (s is not in range).
- `SUB r,v`: d = a - b exactly; r = wrap(d); Z, N from the result; C = (unsigned a < unsigned b); V = (d is not in range). `CMP r,v` sets the flags exactly like `SUB` but does not change r.
- `NEG r`: exactly like `SUB` with a = 0 and b = the value of r, storing the result in r (so C = (r was not 0) and V = (r was -2^31)).
- `MUL r,v`: p = a * b exactly; r = wrap(p); Z, N from the result; C and V are both set to (p is not in range).
- `DIV r,v`: if b = 0, error `"DIV_ZERO"`. Otherwise q = a / b rounded toward zero; r = wrap(q); Z, N from the result; C = false; V = (q is not in range).
- `MOD r,v`: if b = 0, error `"DIV_ZERO"`. Otherwise r = a - b * q with q as for `DIV` (the result is 0 or has the sign of a); Z, N from the result; C = false; V = false.
- `AND`, `OR`, `XOR r,v`: bitwise on the 32-bit two's-complement patterns; `NOT r`: flips every bit of r. Z, N from the result; C = false; V = false.
- `SHL`, `SHR`, `SAR r,v`: the shift count k is the unsigned value of b modulo 32 (so 33 means 1 and -1 means 31). If k = 0: r is unchanged, Z, N from r, C = false, V = false. Otherwise `SHL` shifts left (r = wrap(a * 2^k)) and C = bit (32 - k) of unsigned a; `SHR` is a logical right shift (r = wrap(floor(unsigned a / 2^k))) and C = bit (k - 1) of unsigned a; `SAR` is an arithmetic right shift (r = floor(a / 2^k)) and C = bit (k - 1) of unsigned a. In all three cases Z, N from the result and V = false.
- `JMP L`: jump to L. Conditional jumps jump to L when their condition holds (otherwise IP advances by 1): `JE` Z; `JNE` not Z; `JL` N ≠ V; `JLE` Z or N ≠ V; `JG` (not Z) and N = V; `JGE` N = V; `JB` C; `JBE` C or Z; `JA` (not C) and (not Z); `JAE` not C.
- `LOOP r,L`: r = wrap(r - 1); if the new r is not 0, jump to L. Flags unchanged.
- `CALL L`: if the stack already holds 256 values, error `"STACK_OVERFLOW"`; otherwise push IP + 1 (the number of the next instruction) and jump to L.
- `RET`: if the stack is empty, error `"STACK_UNDERFLOW"`; otherwise pop a value x; if 0 <= x <= n jump to instruction x (x = n means the program halts at step 1 of the next round), otherwise error `"BAD_JUMP"` (and, as for every error, nothing is popped).
- `PUSH v`: error `"STACK_OVERFLOW"` if the stack already holds 256 values; otherwise push b. `POP r`: error `"STACK_UNDERFLOW"` if the stack is empty; otherwise pop into r.
- `IN r`: if the input queue is empty, error `"NO_INPUT"`; otherwise remove its first number and store it in r.
- `OUT v`: append b to the output list.
- `HALT`: stop with status `"HALT"`.

**Result**
Return an object with exactly these keys: `status` (one of `"HALT"`, `"LIMIT"`, `"DIV_ZERO"`, `"STACK_OVERFLOW"`, `"STACK_UNDERFLOW"`, `"BAD_ADDRESS"`, `"BAD_JUMP"`, `"NO_INPUT"`, `"SYNTAX"`), `output` (the array of numbers produced by `OUT`, in order), `steps` (the final value of `steps`) and `registers` (an array with the final values of R0 to R7).

Bounds: `source` has at most 300 lines; `input` has at most 1,000 integers, each in range; 1 <= `maxSteps` <= 10,000,000. Some tests run several million instructions, so assemble the program once and keep the per-instruction work small.

Examples:
{examples}

{trailer}
