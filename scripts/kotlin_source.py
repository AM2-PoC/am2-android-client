#!/usr/bin/env python3


def executable_text(content: str) -> str:
    output = []
    index = 0
    depth = 0
    state = "code"
    while index < len(content):
        pair = content[index:index + 2]
        char = content[index]
        if depth:
            if pair == "/*":
                depth += 1
                index += 2
            elif pair == "*/":
                depth -= 1
                index += 2
            else:
                index += 1
            continue
        if state in {"string", "char"}:
            output.append(char)
            if char == "\\" and index + 1 < len(content):
                output.append(content[index + 1])
                index += 2
                continue
            delimiter = '"' if state == "string" else "'"
            if char == delimiter:
                state = "code"
            index += 1
            continue
        if char == '"':
            state = "string"
            output.append(char)
            index += 1
            continue
        if char == "'":
            state = "char"
            output.append(char)
            index += 1
            continue
        if pair == "/*":
            depth = 1
            index += 2
            continue
        if pair == "//":
            newline = content.find("\n", index + 2)
            if newline < 0:
                break
            output.append("\n")
            index = newline + 1
            continue
        output.append(char)
        index += 1
    return "".join(output)
