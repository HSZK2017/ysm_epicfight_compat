package com.ysmef.compat.model;

/**
 * Render-path resource releaser, implemented by the GPU / CPU skinning
 * packages and registered through {@link YSMMeshLibrary#registerMeshReleaser}.
 *
 * Inverts the old model -> gpu/cpu dependency: the mesh library and the
 * texture store release resources through this interface instead of calling
 * the render-path classes directly, so the model package no longer imports the
 * gpu/cpu packages (breaking the model &lt;-&gt; gpu/cpu package cycle; the
 * render paths keep their one-way data dependency on the model package).
 *
 * Implementations self-register in their static initializer, which runs on
 * the render thread when the path is first used - resources can only exist
 * after the path class was loaded, so the registration is always in place
 * before any disposal is needed.
 */
public interface MeshReleaser {

    /** Release one mesh's GL resources across the implementing render path (render thread). */
    void disposeMesh(YSMMesh mesh);

    /** Release every mesh resource of the implementing render path (resource reload, render thread). */
    void disposeAll();
}
