/**
 * @file        rex/core/fiber_android.cpp
 * @brief       Android (bionic, arm64-v8a) backend para rex::thread::Fiber
 *
 * @copyright   Copyright (c) 2026 naughtybear-restuff-android port.
 *
 * @license     BSD 3-Clause License
 *              See LICENSE file in the project root for full license text.
 *
 * @port        naughtybear-restuff-android: o bionic não expõe a API
 *              ucontext (getcontext/makecontext/swapcontext). A troca de
 *              contexto é feita em assembly AArch64 preservando exatamente
 *              os registradores callee-saved do AAPCS64:
 *                - x19..x28, x29 (FP), x30 (LR), SP
 *                - d8..d15 (metade inferior dos v8..v15)
 *              Semântica idêntica ao backend POSIX (ucontext): a fiber
 *              retoma no ponto exato após o último SwitchTo, com toda a
 *              pilha de chamadas C++ preservada. tls_current_ é trocado
 *              ANTES do swap (mesma ordem do backend POSIX).
 *
 *              O port é exclusivamente arm64-v8a; qualquer outra
 *              arquitectura falha em tempo de compilação (#error).
 */

#include <rex/platform.h>
#if defined(__ANDROID__) && defined(__aarch64__)

#include <rex/assert.h>
#include <rex/thread/fiber.h>

#include <cstdint>

// Implementação em assembly abaixo (mesma TU).
extern "C" void rex_fiber_switch(void* from_ctx, void* to_ctx);

// rex_fiber_switch(void* from_ctx, void* to_ctx)
// Salva o contexto corrente em *from e restaura/retoma *to.
// Layout do bloco (168 bytes, casado com FiberContext em fiber.h):
//   [0]   x19  [8]   x20  [16]  x21  [24]  x22
//   [32]  x23  [40]  x24  [48]  x25  [56]  x26
//   [64]  x27  [72]  x28  [80]  x29  [88]  x30
//   [96]  sp   [104] d8   [112] d9   [120] d10
//   [128] d11  [136] d12  [144] d13  [152] d14  [160] d15
asm(".text\n"
    ".global rex_fiber_switch\n"
    ".type rex_fiber_switch, %function\n"
    "rex_fiber_switch:\n"
    "  mov x9, sp\n"
    "  stp x19, x20, [x0, #0]\n"
    "  stp x21, x22, [x0, #16]\n"
    "  stp x23, x24, [x0, #32]\n"
    "  stp x25, x26, [x0, #48]\n"
    "  stp x27, x28, [x0, #64]\n"
    "  stp x29, x30, [x0, #80]\n"
    "  stp x9, xzr, [x0, #96]\n"
    "  stp d8, d9, [x0, #104]\n"
    "  stp d10, d11, [x0, #120]\n"
    "  stp d12, d13, [x0, #136]\n"
    "  stp d14, d15, [x0, #152]\n"
    "  ldp x19, x20, [x1, #0]\n"
    "  ldp x21, x22, [x1, #16]\n"
    "  ldp x23, x24, [x1, #32]\n"
    "  ldp x25, x26, [x1, #48]\n"
    "  ldp x27, x28, [x1, #64]\n"
    "  ldp x29, x30, [x1, #80]\n"
    "  ldp x9, xzr, [x1, #96]\n"
    "  ldp d8, d9, [x1, #104]\n"
    "  ldp d10, d11, [x1, #120]\n"
    "  ldp d12, d13, [x1, #136]\n"
    "  ldp d14, d15, [x1, #152]\n"
    "  mov sp, x9\n"
    "  br x30\n"
    // Pilha não-executável (nota GNU-stack vazia) e volta ao .text.
    ".section .note.GNU-stack,\"\",@progbits\n"
    ".text\n");

namespace rex::thread {

thread_local Fiber* Fiber::tls_current_ = nullptr;

Fiber* Fiber::ConvertCurrentThread() {
  auto* f = new Fiber();
  // O contexto é preenchido no primeiro SwitchTo que sai desta fiber.
  f->is_thread_fiber_ = true;
  tls_current_ = f;
  return f;
}

Fiber* Fiber::Create(size_t stack_size, void (*entry)(void*), void* arg) {
  assert_not_null(entry);
  auto* f = new Fiber();
  f->entry_ = entry;
  f->arg_ = arg;
  f->stack_.resize(stack_size);

  // Primeira entrada: x30 = trampolim, sp = topo da pilha alinhado a 16
  // (AAPCS64). FP=0 encerra a cadeia de frame pointers, como numa raiz de
  // thread. Demais registradores partem zerados.
  uintptr_t stack_top = reinterpret_cast<uintptr_t>(f->stack_.data()) + f->stack_.size();
  stack_top &= ~uintptr_t(0xF);

  f->context_ = FiberContext{};
  f->context_.x[11] = reinterpret_cast<uint64_t>(&Fiber::Trampoline);
  f->context_.x[12] = static_cast<uint64_t>(stack_top);
  return f;
}

/*static*/ void Fiber::Trampoline() {
  // tls_current_ já aponta para esta fiber (setado em SwitchTo antes do
  // swap). Mesma semântica de uc_link=nullptr / fibers Win32: a entrada
  // não retorna — a fiber termina trocando de contexto.
  Fiber* f = tls_current_;
  f->entry_(f->arg_);
  assert_always("Fiber entry retornou — fibers devem trocar de contexto "
                "antes de retornar");
}

void Fiber::SwitchTo(Fiber* target) {
  Fiber* from = tls_current_;
  assert_not_null(from);
  assert_not_null(target);
  tls_current_ = target;
  rex_fiber_switch(&from->context_, &target->context_);
}

void Fiber::Destroy() {
  // Thread fibers are destroyed from the owning thread itself.
  if (is_thread_fiber_) {
    tls_current_ = nullptr;
  } else {
    assert(this != tls_current_ && "Destroy called on the currently running fiber");
  }
  // stack_ é liberado pelo destrutor do vector.
  delete this;
}

}  // namespace rex::thread

#else
#error "fiber_android.cpp requer __ANDROID__ e __aarch64__ (port é arm64-v8a only)"
#endif  // defined(__ANDROID__) && defined(__aarch64__)
