# rex_target_pch — DESABILITADO no port Android.
#
# Motivo: clang + libstdc++ (GCC 13) + PCH quebra <expected> — os macros de
# feature (__glibcxx_expected) do bits/version.h não sobrevivem ao estado
# congelado do PCH, e o include de <expected> no TU vira no-op vazio
# ("no template named 'expected' in namespace 'std'"). Sem PCH o header
# resolve normalmente. Custo: rebuild um pouco mais lento; zero impacto
# funcional (PCH é otimização pura).
function(rex_target_pch target)
    # no-op: PCH desligado (ver comentário acima)
endfunction()
