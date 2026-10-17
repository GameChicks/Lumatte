#include "mobile_film_gles.hpp"
#include "mobile_shaders.hpp"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <cstring>
#include <memory>
#include <sstream>
#include <utility>

namespace lfm::mobile {
namespace {
void gl_check(const char* operation) {
    const GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        std::ostringstream text; text << operation << " (GL 0x" << std::hex << error << ')';
        throw GlesError(text.str());
    }
}
void egl_check(bool ok, const char* operation) {
    if (!ok) {
        std::ostringstream text; text << operation << " (EGL 0x" << std::hex << eglGetError() << ')';
        throw GlesError(text.str());
    }
}
GLuint compile(GLenum type, const char* source) {
    const GLuint shader = glCreateShader(type);
    if (!shader) throw GlesError("Cannot create film shader");
    glShaderSource(shader, 1, &source, nullptr); glCompileShader(shader);
    GLint success = 0; glGetShaderiv(shader, GL_COMPILE_STATUS, &success);
    if (!success) {
        char log[2048]{}; glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        glDeleteShader(shader); throw GlesError(std::string("Film shader compile: ") + log);
    }
    return shader;
}
void texture_parameters(GLenum target) {
    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (target == GL_TEXTURE_3D) glTexParameteri(target, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
    glTexParameteri(target, GL_TEXTURE_BASE_LEVEL, 0); glTexParameteri(target, GL_TEXTURE_MAX_LEVEL, 0);
}

class Device {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint program = 0, vao = 0, framebuffer = 0, vertex_buffer = 0;
    // Input, response, development, scan, lens, halo, JPEG look, output.
    std::array<GLuint, 8> textures{};
    int tile_edge = 0, max_3d = 0;
    std::string loaded_model;
    bool bound = false;

    GLint uniform(const char* name) {
        const GLint id = glGetUniformLocation(program, name);
        if (id < 0) throw GlesError(std::string("Missing film uniform: ") + name);
        return id;
    }
    void triple(const char* name, const Colour& colour) { glUniform3fv(uniform(name), 1, colour.data()); }
    void bind_texture(int unit, GLenum target, int texture_index) {
        glActiveTexture(GL_TEXTURE0 + unit); glBindTexture(target, textures[size_t(texture_index)]);
    }
    void upload_stage(int unit, const Table& table) {
        if (table.size > max_3d) throw GlesError("Film table exceeds GL_MAX_3D_TEXTURE_SIZE");
        bind_texture(unit, GL_TEXTURE_3D, unit); texture_parameters(GL_TEXTURE_3D);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB32F, table.size, table.size, table.size, 0, GL_RGB, GL_FLOAT, table.values.data());
        gl_check("Upload film stage");
    }
    void upload_field(int unit, const Field& field) {
        constexpr float empty[3]{};
        bind_texture(unit, GL_TEXTURE_2D, unit); texture_parameters(GL_TEXTURE_2D);
        const auto width = field.values.empty() ? 1u : field.width, height = field.values.empty() ? 1u : field.height;
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB32F, GLsizei(width), GLsizei(height), 0, GL_RGB, GL_FLOAT,
                     field.values.empty() ? empty : field.values.data());
        gl_check("Upload film field");
    }
    void initialize() {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        egl_check(display != EGL_NO_DISPLAY, "Get EGL display");
        egl_check(eglInitialize(display, nullptr, nullptr), "Initialize EGL");
        egl_check(eglBindAPI(EGL_OPENGL_ES_API), "Bind GLES API");
        const EGLint config_attributes[]{EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
        EGLConfig config{}; EGLint count = 0;
        egl_check(eglChooseConfig(display, config_attributes, &config, 1, &count), "Choose GLES3 config");
        if (count != 1) throw GlesError("No GLES3 pbuffer config");
        const EGLint context_attributes[]{EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
        egl_check(context != EGL_NO_CONTEXT, "Create GLES3 context");
        const EGLint surface_attributes[]{EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface = eglCreatePbufferSurface(display, config, surface_attributes);
        egl_check(surface != EGL_NO_SURFACE, "Create private pbuffer");
        make_current();
        GLint version = 0, units = 0, max_texture = 0, viewport[2]{}, precision = 0, range[2]{};
        glGetIntegerv(GL_MAJOR_VERSION, &version); glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &units);
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_texture); glGetIntegerv(GL_MAX_VIEWPORT_DIMS, viewport);
        glGetIntegerv(GL_MAX_3D_TEXTURE_SIZE, &max_3d);
        glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER, GL_HIGH_FLOAT, range, &precision);
        gl_check("Query film GPU limits");
        if (version < 3 || units < 7 || precision < 23 || range[0] < 127 || range[1] < 127)
            throw GlesError("Film requires GLES3 with float32 fragment precision and seven samplers");
        tile_edge = std::min({1024, max_texture, viewport[0], viewport[1]});
        if (tile_edge <= 0 || max_texture < 512) throw GlesError("Insufficient film texture/viewport limits");
        GLuint vertex = compile(GL_VERTEX_SHADER, shaders::vertex), fragment = 0;
        try {
            fragment = compile(GL_FRAGMENT_SHADER, shaders::fragment);
            program = glCreateProgram();
            if (!program) throw GlesError("Cannot create film program");
            glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program);
            GLint linked = 0; glGetProgramiv(program, GL_LINK_STATUS, &linked);
            if (!linked) {
                char log[2048]{}; glGetProgramInfoLog(program, sizeof(log), nullptr, log);
                throw GlesError(std::string("Film shader link: ") + log);
            }
            glDetachShader(program, vertex); glDetachShader(program, fragment);
        } catch (...) { glDeleteShader(vertex); if (fragment) glDeleteShader(fragment); throw; }
        glDeleteShader(vertex); glDeleteShader(fragment);
        glGenTextures(GLsizei(textures.size()), textures.data()); glGenVertexArrays(1, &vao); glGenFramebuffers(1, &framebuffer);
        glUseProgram(program); glBindVertexArray(vao);
        // Same fullscreen triangle; explicit input avoids no-attribute gl_VertexID driver failures.
        glGenBuffers(1,&vertex_buffer); glBindBuffer(GL_ARRAY_BUFFER,vertex_buffer);
        constexpr float positions[]{-1.f,-1.f,3.f,-1.f,-1.f,3.f};
        glBufferData(GL_ARRAY_BUFFER,sizeof(positions),positions,GL_STATIC_DRAW);
        glEnableVertexAttribArray(0); glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,nullptr);
        constexpr const char* samplers[]{"uInput", "uResponse", "uDevelopment", "uScan", "uLens", "uHalo", "uRemjet"};
        for (int i = 0; i < 7; ++i) glUniform1i(uniform(samplers[i]), i);
        bind_texture(0, GL_TEXTURE_2D, 0); texture_parameters(GL_TEXTURE_2D);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, tile_edge, tile_edge, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        bind_texture(0, GL_TEXTURE_2D, 7); texture_parameters(GL_TEXTURE_2D);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, tile_edge, tile_edge, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[7], 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw GlesError("Film RGBA8 framebuffer incomplete");
        glDisable(GL_BLEND); glDisable(GL_DITHER); glDisable(GL_DEPTH_TEST); glDisable(GL_SCISSOR_TEST);
        glPixelStorei(GL_PACK_ALIGNMENT, 1); glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0); glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        gl_check("Allocate film GPU resources");
        complete();
    }
    void destroy() noexcept {
        if (display == EGL_NO_DISPLAY) return;
        if (context != EGL_NO_CONTEXT && surface != EGL_NO_SURFACE && eglMakeCurrent(display, surface, surface, context)) {
            glFinish(); glDeleteTextures(GLsizei(textures.size()), textures.data());
            if (program) glDeleteProgram(program);
            if (vao) glDeleteVertexArrays(1, &vao);
            if (vertex_buffer) glDeleteBuffers(1,&vertex_buffer);
            if (framebuffer) glDeleteFramebuffers(1, &framebuffer);
        }
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        // The default display may also serve Android UI. Destroy ours, never eglTerminate it.
        display = EGL_NO_DISPLAY; context = EGL_NO_CONTEXT; surface = EGL_NO_SURFACE; bound = false;
    }
public:
    Device() { try { initialize(); } catch (...) { destroy(); throw; } }
    ~Device() { destroy(); }
    void make_current() {
        egl_check(eglMakeCurrent(display, surface, surface, context), "Bind private film context"); bound = true;
    }
    void finish() noexcept {
        if (!bound) return;
        glFinish(); eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); bound = false;
    }
    void complete() {
        glFinish(); gl_check("Complete film GPU work");
        egl_check(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT), "Unbind film context");
        bound = false;
    }
    void render(Image image, const Model& model, const Effects& effects, const std::string& key,
                uint32_t source_width, uint32_t source_height, uint64_t seed, const Check& check) {
        make_current();
        struct Drain { Device& device; ~Drain() { device.finish(); } } drain{*this};
        check();
        std::array<float, 256> decode_lut{};
        for (int i = 0; i < 256; ++i) decode_lut[size_t(i)] = decode(float(i) / 255);
        const Spatial spatial = prepare_spatial(image, model, effects, decode_lut, check);
        check();
        if (image.stride % 4 != 0) throw GlesError("Film RGBA row stride is not pixel aligned");
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        if (loaded_model != key) {
            loaded_model.clear(); // Interrupted uploads must never retain the previous model's cache key.
            upload_stage(1, model.response); check(); upload_stage(2, model.development); check(); upload_stage(3, model.scan);
            loaded_model = key;
        } else for (int unit = 1; unit <= 3; ++unit) bind_texture(unit, GL_TEXTURE_3D, unit);
        upload_field(4, spatial.lens); check(); upload_field(5, spatial.halation);
        check(); upload_field(6,spatial.remjet);
        glUseProgram(program); glBindVertexArray(vao); glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        triple("uResponseLow", model.response.minimum); triple("uResponseHigh", model.response.maximum);
        triple("uDevelopmentLow", model.development.minimum); triple("uDevelopmentHigh", model.development.maximum);
        triple("uScanLow", model.scan.minimum); triple("uScanHigh", model.scan.maximum);
        triple("uHalationMix", spatial.halation_mix);
        glUniform1i(uniform("uSpectralReturn"), spatial.spectral_return);
        triple("uReturnRow0",spatial.return_matrix[0]); triple("uReturnRow1",spatial.return_matrix[1]);
        triple("uReturnRow2",spatial.return_matrix[2]);
        triple("uParticleScale", effects.particle_scale); triple("uFloor", effects.floor);
        triple("uUniformity", effects.uniformity); triple("uDensityMaximum", effects.density_max);
        glUniform2i(uniform("uImageSize"), GLint(image.width), GLint(image.height));
        glUniform2i(uniform("uSourceSize"), GLint(source_width), GLint(source_height));
        glUniform1f(uniform("uSoftness"), spatial.softness); glUniform1f(uniform("uExposureGain"), effects.exposure_gain);
        const float pixel_um = effects.format_mm * 1000 / std::max(image.width, image.height);
        glUniform1f(uniform("uPixelArea"), pixel_um * pixel_um); glUniform1f(uniform("uParticleArea"), effects.particle_area);
        const float spacing = effects.grain_spacing_um > 0 ? std::max(.7f,
            effects.grain_spacing_um * std::max(source_width, source_height) / (effects.format_mm * 1000)) : 1.4f;
        glUniform1f(uniform("uGrainSpacing"), spacing);
        glUniform1f(uniform("uRemjetAmount"),spatial.remjet.values.empty()?0.f:effects.remjet_look);
        glUniform1i(uniform("uHalation"), !spatial.halation.values.empty()); glUniform1i(uniform("uGrain"), effects.grain);
        glUniform1ui(uniform("uSeed"), uint32_t(seed) ^ hash(uint32_t(seed >> 32)));
        gl_check("Configure film GPU");
        std::vector<uint8_t> readback(size_t(tile_edge) * tile_edge * 4);
        bind_texture(0, GL_TEXTURE_2D, 0);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, GLint(image.stride / 4));
        for (uint32_t top = 0; top < image.height; top += uint32_t(tile_edge)) {
            for (uint32_t left = 0; left < image.width; left += uint32_t(tile_edge)) {
                check();
                const uint32_t width = std::min(uint32_t(tile_edge), image.width - left);
                const uint32_t height = std::min(uint32_t(tile_edge), image.height - top);
                const uint8_t* input = image.pixels + size_t(top) * image.stride + size_t(left) * 4;
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, GLsizei(width), GLsizei(height), GL_RGBA, GL_UNSIGNED_BYTE, input);
                glViewport(0, 0, GLsizei(width), GLsizei(height)); glUniform2i(uniform("uOrigin"), GLint(left), GLint(top));
                glDrawArrays(GL_TRIANGLES, 0, 3); gl_check("Render film tile"); check();
                glReadPixels(0, 0, GLsizei(width), GLsizei(height), GL_RGBA, GL_UNSIGNED_BYTE, readback.data());
                gl_check("Read film tile"); check();
                // Alpha mismatch is the shader's numerical-error sentinel, before any tile commit.
                for (uint32_t y = 0; y < height; ++y) {
                    check();
                    const uint8_t* original = input + size_t(y) * image.stride;
                    const uint8_t* output = readback.data() + size_t(y) * width * 4;
                    for (uint32_t x = 0; x < width; ++x)
                        if (output[size_t(x)*4 + 3] != original[size_t(x)*4 + 3]) throw GlesError("Invalid numerical film GPU output");
                }
                // Texture row 0, GL row 0 and the logical top row use the same index; no second flip.
                for (uint32_t y = 0; y < height; ++y) {
                    check();
                    std::memcpy(image.pixels + size_t(top + y) * image.stride + size_t(left) * 4,
                                readback.data() + size_t(y) * width * 4, size_t(width) * 4);
                }
            }
        }
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0); check(); complete();
    }
};
thread_local std::unique_ptr<Device> device; // Owned exclusively by the shared mobile film worker.
}
void render_gles(Image image, const Model& model, const Effects& effects, const std::string& model_key,
                 uint32_t source_width, uint32_t source_height, uint64_t seed, const Check& check) {
    try {
        if (!device) device = std::make_unique<Device>();
        device->render(image, model, effects, model_key, source_width, source_height, seed, check);
    } catch (const GlesError&) { device.reset(); throw; }
}
void release_idle_gles() { device.reset(); }
}
