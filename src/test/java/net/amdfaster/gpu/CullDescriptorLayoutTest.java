package net.amdfaster.gpu;

import net.amdfaster.gpu.CullBindings.Binding;
import net.amdfaster.vk.RootSignatureBudget;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CullDescriptorLayoutTest {

    @Test
    void theDescriptorTypesMatchVulkan() {
        assertEquals(org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                CullBindings.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        assertEquals(org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                CullBindings.DESCRIPTOR_TYPE_UNIFORM_BUFFER);
        assertEquals(org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                CullBindings.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT, CullBindings.STAGE_COMPUTE);
    }

    @Test
    void theFrustumPassBindsFourDescriptors() {
        List<Binding> bindings = CullBindings.descriptorBindings();
        assertEquals(4, bindings.size());
        assertEquals(CullBindings.FRAME_UBO_BINDING, bindings.get(0).binding());
        assertEquals(CullBindings.MESHLET_BUFFER_BINDING, bindings.get(1).binding());
        assertEquals(CullBindings.DRAW_COMMAND_BUFFER_BINDING, bindings.get(2).binding());
        assertEquals(CullBindings.COUNTER_BUFFER_BINDING, bindings.get(3).binding());
    }

    @Test
    void theOcclusionPassAddsThePyramidAndNothingElse() {
        List<Binding> base = CullBindings.descriptorBindings();
        List<Binding> occlusion = CullBindings.occlusionDescriptorBindings();
        assertEquals(base.size() + 1, occlusion.size());
        Binding added = occlusion.get(occlusion.size() - 1);
        assertEquals(CullBindings.HIZ_IMAGE_BINDING, added.binding());
        assertEquals(CullBindings.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, added.descriptorType());
        // The shared bindings must be identical, or the two pipelines cannot share a pool.
        for (int i = 0; i < base.size(); i++) {
            assertEquals(base.get(i), occlusion.get(i), "binding " + i + " differs between the passes");
        }
    }

    @Test
    void bindingNumbersAreUniqueAndDense() {
        List<Binding> bindings = CullBindings.occlusionDescriptorBindings();
        for (int i = 0; i < bindings.size(); i++) {
            assertEquals(i, bindings.get(i).binding(), "bindings must be 0..n-1 in order");
        }
    }

    @Test
    void everyBindingIsComputeOnly() {
        for (Binding b : CullBindings.occlusionDescriptorBindings()) {
            assertEquals(CullBindings.STAGE_COMPUTE, b.stageFlags(), b.name());
            assertEquals(1, b.count(), b.name());
        }
    }

    @Test
    void theFrameUniformIsNotAStorageBuffer() {
        // A UBO read by 64 threads at once comes from constant cache; a storage buffer would go
        // through the normal read path and cost bandwidth for data every thread wants.
        assertEquals(CullBindings.DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                CullBindings.descriptorBindings().get(0).descriptorType());
    }

    @Test
    void theRootSignatureStaysInsideTheBudgetWithBothLayouts() {
        RootSignatureBudget.Verdict frustum =
                RootSignatureBudget.check(CullBindings.PUSH_CONSTANT_BYTES, 1);
        assertTrue(frustum.fits(), frustum.detail());
        // Two sets bound at once would be the occlusion pass sharing the frame set: still well in.
        RootSignatureBudget.Verdict both =
                RootSignatureBudget.check(CullBindings.PUSH_CONSTANT_BYTES, 2);
        assertTrue(both.fits(), both.detail());
    }

    @Test
    void theCompiledSpirvIsInTheJar() {
        for (String path : new String[] {CullBindings.CULL_SPIRV_PATH,
                CullBindings.OCCLUSION_SPIRV_PATH}) {
            try (InputStream in = CullDescriptorLayoutTest.class.getResourceAsStream(path)) {
                assertNotNull(in, "missing " + path + ": the compileShaders build task did not run");
                byte[] bytes = in.readAllBytes();
                assertTrue(bytes.length > 20, path + " is suspiciously short: " + bytes.length);
                int magic = (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8)
                        | ((bytes[2] & 0xFF) << 16) | ((bytes[3] & 0xFF) << 24);
                assertEquals(0x07230203, magic, path + " is not SPIR-V");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
