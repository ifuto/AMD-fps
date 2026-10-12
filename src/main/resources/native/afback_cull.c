/*
 * AMD-Faster backend: culling kernels.
 *
 * Compiled separately from the mod and launched by it as a child process. The mod cannot call native
 * code directly -- on Java 21 the Foreign Function and Memory API is still a preview feature and needs
 * --enable-preview, which a mod has no way to pass -- so it talks to this over a socket instead. The
 * same restriction blocks the Vector API, which needs --add-modules=jdk.incubator.vector. Both are
 * unavailable to a mod and both are available here.
 *
 * Layout is structure-of-arrays throughout. Zen implements gather and scatter far more slowly than a
 * contiguous load, so every access in these kernels is a run of adjacent elements and none of them
 * gathers.
 *
 * Build:  gcc -O3 -mavx512f -mavx512bw -o afback_cull afback_cull.c
 * Test:   ./afback_cull --selftest
 *
 * Every vectorised kernel here has a scalar twin, and --selftest runs both over the same input and
 * compares bit for bit. A kernel that is fast and occasionally wrong produces geometry that vanishes
 * once every few thousand frames in a way nobody can reproduce; that is exactly what the twin is for.
 */

#include <immintrin.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LANES 16
#define PLANES 6

/* A plane is ax + by + cz + d >= 0 on the visible side. */
typedef struct {
    float a[PLANES];
    float b[PLANES];
    float c[PLANES];
    float d[PLANES];
} planes_t;

typedef struct {
    const float *min_x;
    const float *min_y;
    const float *min_z;
    const float *max_x;
    const float *max_y;
    const float *max_z;
} boxes_t;

/*
 * Scalar twin. This is the reference: the vector path is checked against it, and the mod's Java side
 * carries the same logic so that losing the backend degrades to slower rather than to different.
 *
 * The test is the standard conservative one. For each plane the box corner farthest along the plane
 * normal -- the "positive vertex" -- is the last part of the box to leave the half-space. If even that
 * corner is outside, the whole box is. It never rejects a box that is visible, which is the only
 * property that actually matters for a culler: a false negative is a frame-rate cost, a false positive
 * is a hole in the world.
 */
static uint8_t cull_scalar_one(const planes_t *p, const boxes_t *bx, size_t i) {
    for (int k = 0; k < PLANES; k++) {
        float px = p->a[k] > 0.0f ? bx->max_x[i] : bx->min_x[i];
        float py = p->b[k] > 0.0f ? bx->max_y[i] : bx->min_y[i];
        float pz = p->c[k] > 0.0f ? bx->max_z[i] : bx->min_z[i];
        if (p->a[k] * px + p->b[k] * py + p->c[k] * pz + p->d[k] < 0.0f) {
            return 0;
        }
    }
    return 1;
}

static void cull_scalar(const planes_t *p, const boxes_t *bx, size_t count, uint8_t *visible) {
    for (size_t i = 0; i < count; i++) {
        visible[i] = cull_scalar_one(p, bx, i);
    }
}

#if defined(__AVX512F__)

/*
 * Sixteen boxes at once.
 *
 * The positive-vertex selection is a blend on the sign of the plane coefficient, which is why the
 * layout is SoA: a plane's coefficient broadcasts to all sixteen lanes and each box's coordinate is
 * already contiguous, so the blend costs one compare and one masked move per axis rather than a
 * branch per box per plane. Ninety-six branches per sixteen boxes is what the scalar path pays, and on
 * a loop whose boxes alternate in and out of the frustum they mispredict constantly.
 *
 * Zen 5 raised the minimum latency of a 512-bit operation from one cycle to two, so the six planes are
 * accumulated into a single mask with no dependency between them: each plane's compare is independent
 * of every other, and the mask AND is the only thing serialising the loop.
 */
static void cull_avx512_block(const planes_t *p, const boxes_t *bx, size_t base, uint8_t *visible) {
    __m512 min_x = _mm512_loadu_ps(bx->min_x + base);
    __m512 min_y = _mm512_loadu_ps(bx->min_y + base);
    __m512 min_z = _mm512_loadu_ps(bx->min_z + base);
    __m512 max_x = _mm512_loadu_ps(bx->max_x + base);
    __m512 max_y = _mm512_loadu_ps(bx->max_y + base);
    __m512 max_z = _mm512_loadu_ps(bx->max_z + base);

    const __m512 zero = _mm512_setzero_ps();
    __mmask16 inside = (__mmask16)0xFFFF;

    for (int k = 0; k < PLANES; k++) {
        __m512 a = _mm512_set1_ps(p->a[k]);
        __m512 b = _mm512_set1_ps(p->b[k]);
        __m512 c = _mm512_set1_ps(p->c[k]);
        __m512 d = _mm512_set1_ps(p->d[k]);

        __mmask16 sa = _mm512_cmp_ps_mask(a, zero, _CMP_GT_OQ);
        __mmask16 sb = _mm512_cmp_ps_mask(b, zero, _CMP_GT_OQ);
        __mmask16 sc = _mm512_cmp_ps_mask(c, zero, _CMP_GT_OQ);

        __m512 px = _mm512_mask_blend_ps(sa, min_x, max_x);
        __m512 py = _mm512_mask_blend_ps(sb, min_y, max_y);
        __m512 pz = _mm512_mask_blend_ps(sc, min_z, max_z);

        __m512 dist = _mm512_fmadd_ps(a, px, _mm512_fmadd_ps(b, py, _mm512_fmadd_ps(c, pz, d)));
        inside = _kand_mask16(inside, _mm512_cmp_ps_mask(dist, zero, _CMP_GE_OQ));
    }

    _mm512_mask_storeu_epi8(visible + base, inside, _mm512_set1_epi8(1));
    _mm512_mask_storeu_epi8(visible + base, (__mmask16)~inside, _mm512_setzero_si512());
}

#endif /* __AVX512F__ */

static void cull_auto(const planes_t *p, const boxes_t *bx, size_t count, uint8_t *visible) {
#if defined(__AVX512F__)
    if (__builtin_cpu_supports("avx512f")) {
        size_t i = 0;
        for (; i + LANES <= count; i += LANES) {
            cull_avx512_block(p, bx, i, visible);
        }
        for (; i < count; i++) {
            visible[i] = cull_scalar_one(p, bx, i);
        }
        return;
    }
#endif
    cull_scalar(p, bx, count, visible);
}

/* ---------------------------------------------------------------- self-test */

static uint32_t rng_state = 0x12345678u;
static uint32_t next_rand(void) {
    rng_state ^= rng_state << 13;
    rng_state ^= rng_state >> 17;
    rng_state ^= rng_state << 5;
    return rng_state;
}
static float next_float(float lo, float hi) {
    return lo + (hi - lo) * (float)(next_rand() & 0xFFFF) / 65535.0f;
}

/*
 * Differential check. The two paths must agree on every box, including boxes that sit exactly on a
 * plane -- that is where a difference between a fused and an unfused multiply-add would show up first,
 * and where an off-by-one in the comparison would hide.
 */
static int selftest(void) {
    enum { COUNT = 1000 };
    static float min_x[COUNT], min_y[COUNT], min_z[COUNT];
    static float max_x[COUNT], max_y[COUNT], max_z[COUNT];
    static uint8_t ref[COUNT], out[COUNT];
    planes_t p;

    /* A frustum whose planes straddle the origin, so that roughly half the boxes fail and half pass.
       A test where everything is visible, or nothing is, passes for the wrong reason. */
    for (int k = 0; k < PLANES; k++) {
        p.a[k] = next_float(-1.0f, 1.0f);
        p.b[k] = next_float(-1.0f, 1.0f);
        p.c[k] = next_float(-1.0f, 1.0f);
        p.d[k] = next_float(-8.0f, 8.0f);
    }

    for (int i = 0; i < COUNT; i++) {
        min_x[i] = next_float(-32.0f, 32.0f);
        min_y[i] = next_float(-32.0f, 32.0f);
        min_z[i] = next_float(-32.0f, 32.0f);
        max_x[i] = min_x[i] + next_float(0.0f, 16.0f);
        max_y[i] = min_y[i] + next_float(0.0f, 16.0f);
        max_z[i] = min_z[i] + next_float(0.0f, 16.0f);
    }

    boxes_t bx = {min_x, min_y, min_z, max_x, max_y, max_z};

    cull_scalar(&p, &bx, COUNT, ref);
    memset(out, 0xAA, sizeof(out));
    cull_auto(&p, &bx, COUNT, out);

    int mismatches = 0;
    int visible = 0;
    for (int i = 0; i < COUNT; i++) {
        visible += ref[i] != 0;
        if (ref[i] != out[i]) {
            if (mismatches < 5) {
                printf("  mismatch at %d: scalar=%u vector=%u\n", i, ref[i], out[i]);
            }
            mismatches++;
        }
    }

    printf("  boxes=%d visible=%d culled=%d mismatches=%d\n", COUNT, visible, COUNT - visible, mismatches);
    if (mismatches != 0) {
        printf("  FAIL: the vector path disagrees with the scalar twin\n");
        return 1;
    }
    /* A culler that passes everything is not broken, it is useless. If the random frustum kept
       everything visible the test proved nothing. */
    if (visible == 0 || visible == COUNT) {
        printf("  FAIL: degenerate case, nothing was actually decided\n");
        return 1;
    }
    printf("  OK: scalar and vector agree\n");
    return 0;
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--selftest") == 0) {
#if defined(__AVX512F__)
        /* __builtin_cpu_supports returns the raw feature bit, not a bool, so normalise it before
           printing or the log reads as a bitmask and looks like a bug. */
        printf("afback_cull: AVX-512 compiled in, runtime support = %s\n",
               __builtin_cpu_supports("avx512f") ? "yes" : "no");
#else
        printf("afback_cull: AVX-512 not compiled in, scalar only\n");
#endif
        return selftest();
    }
    fprintf(stderr, "afback_cull: protocol server not wired in this build; use --selftest\n");
    return 2;
}
