# Runs only as part of a user-requested native build, never during source work.
file(READ "${INPUT}" shader_hex HEX)
string(REGEX REPLACE "(..)" "0x\\1," shader_bytes "${shader_hex}")
file(WRITE "${OUTPUT}" "#pragma once\ninline constexpr unsigned char shader_${SYMBOL}[] = {${shader_bytes}};\n")
