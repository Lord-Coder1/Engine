package resources.meshes;

import components.renderables.*;
import core.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.joml.*;
import org.lwjgl.*;
import org.lwjgl.assimp.*;
import static org.lwjgl.assimp.Assimp.aiGetErrorString;
import static org.lwjgl.assimp.Assimp.aiImportFile;
import static org.lwjgl.assimp.Assimp.aiProcess_CalcTangentSpace;
import static org.lwjgl.assimp.Assimp.aiProcess_JoinIdenticalVertices;
import static org.lwjgl.assimp.Assimp.aiProcess_Triangulate;
import org.lwjgl.opengl.*;
import resources.ResourceManager.ResourceState;
import resources.*;
import toolbox.annotations.*;

/**
 * Stores a mesh's data. You can load a mesh only once, if you try to load it
 * twice, you get reference to the already loaded one. You can specify the
 * StaticMesh's data store policy including when and where the data should be
 * stored.
 *
 * @see #loadModel(String path)
 */
public class StaticMesh implements Mesh {

    /**
     * The mesh's VAO.
     */
    private Vao vao;
    /**
     * Vertex count.
     */
    private final int vertexCount;
    /**
     * Triangle count.
     */
    private final int faceCount;
    /**
     * Furthest vertex distance.
     */
    private float furthestVertexDistance;
    /**
     * Axis alligned bounding box's min x, y and z values.
     */
    private final Vector3f aabbMin = new Vector3f();
    /**
     * Axis alligned bounding box's max x, y and z values.
     */
    private final Vector3f aabbMax = new Vector3f();
    /**
     * This is the loaded model's indexth mesh.
     */
    private final int elementIndex;
    /**
     * Stores the mesh's position data.
     */
    private AIVector3D.Buffer position;
    /**
     * Stores the mesh's texture coordinate data.
     */
    private AIVector3D.Buffer uv;
    /**
     * Stores the mesh's normal vector data.
     */
    private AIVector3D.Buffer normal;
    /**
     * Stores the mesh's tangent vector data.
     */
    private AIVector3D.Buffer tangent;
    /**
     * Stores the mesh's index data.
     */
    private IntBuffer indices;
    /**
     * Stores meta data about this mesh.
     */
    private final LoadableResourceMetaData meta = new LoadableResourceMetaData();

    /**
     * Initializes a new StaticMesh to the given values.
     *
     * @param mesh mesh
     * @param path model's relative path (with extension like
     * "res/models/myModel.obj")
     * @param index the loadaed model's indexth mesh
     */
    private StaticMesh(@NotNull AIMesh mesh, @NotNull String path, int index) {
        faceCount = mesh.mNumFaces();
        vertexCount = faceCount * 3;
        computeFrustumCullingData(mesh);
        meta.setPath(path);
        elementIndex = index;
        meta.setLastActiveToNow();
        meta.setDataStorePolicy(ResourceState.VRAM);

        hddToRam(mesh);
        ramToVram();

        computeDataSize();
        ResourceManager.addMesh(path + "." + index, this);
    }

    //
    //loading-saving------------------------------------------------------------
    //
    /**
     * Loads a model from the given path into meshes. You can load a mesh only
     * once, if you try to load it twice, you get reference to the already
     * loaded one.
     *
     * @param path model's relative path (with extension like
     * "res/models/myModel.obj")
     * @return lsit of model's meshes
     */
    @NotNull
    public static List<StaticMesh> loadModel(@NotNull String path) {
        AIScene scene = getSceneAssimp(path);
        List<StaticMesh> meshes = new ArrayList<>();
        int meshCount = scene.mNumMeshes();
        PointerBuffer meshesBuffer = scene.mMeshes();

        for (int i = 0; i < meshCount; ++i) {
            String key = path + "." + i;
            StaticMesh me = (StaticMesh) ResourceManager.getMesh(key);
            if (me == null) {
                me = new StaticMesh(AIMesh.create(meshesBuffer.get(i)), path, i);
            }
            meshes.add(me);
        }
        return meshes;
    }

    /**
     * Loads a model from the given path into meshes, and adds each mesh as a
     * MeshComponent to it's own GameObject.
     *
     * @param path model's relative path (with extension like
     * "res/models/myModel.obj")
     * @return list of GameObjects
     */
    @NotNull
    public static List<GameObject> loadModelToGameObjects(@NotNull String path) {
        List<GameObject> list = new ArrayList<>();
        for (StaticMesh me : loadModel(path)) {
            GameObject g = new GameObject();
            g.addComponent(new MeshComponent(me));
            list.add(g);
        }
        return list;
    }

    /**
     * Loads a model from the given path into meshes, and adds all the meshes as
     * MeshComponents to a single GameObject.
     *
     * @param path model's relative path (with extension like
     * "res/models/myModel.obj")
     * @return GameObject
     */
    @NotNull
    public static GameObject loadModelToGameObject(@NotNull String path) {
        GameObject g = new GameObject();
        for (StaticMesh me : loadModel(path)) {
            g.addComponent(new MeshComponent(me));
        }
        return g;
    }

    /**
     * Returns the model's scene stored in the given path.
     *
     * @param path model's relative path (with extension like
     * "res/models/myModel.obj")
     * @return model's scene
     *
     * @throws IllegalArgumentException if there is no file on the given path
     * @throws IllegalStateException if assimp can't load the data from the file
     */
    @NotNull
    private static AIScene getSceneAssimp(@NotNull String path) {
        File file = new File(path);
        if (!file.exists()) {
            throw new IllegalArgumentException(path + " file doesn't exist");
        }
        AIScene scene = aiImportFile(file.getAbsolutePath(), aiProcess_JoinIdenticalVertices | aiProcess_Triangulate | aiProcess_CalcTangentSpace);
        if (scene == null) {
            throw new IllegalStateException(aiGetErrorString());
        }
        return scene;
    }

    /**
     * Computes the mesh's size.
     */
    private void computeDataSize() {
        int FLOAT_SIZE = 4;
        int INT_SIZE = 4;

        int size = 0;
        size += position.capacity() * FLOAT_SIZE;
        size += uv.capacity() * FLOAT_SIZE;
        size += normal.capacity() * FLOAT_SIZE;
        size += tangent.capacity() * FLOAT_SIZE;
        size += indices.capacity() * INT_SIZE;

        meta.setDataSize(size);
    }

    /**
     * Computes the mesh's indices buffer.
     *
     * @param mesh mesh
     * @return indices buffer
     *
     * @throws IllegalStateException a face is not a triangle
     */
    @NotNull
    private IntBuffer computeIndicesBuffer(@NotNull AIMesh mesh) {
        AIFace.Buffer facesBuffer = mesh.mFaces();
        IntBuffer elementArrayBufferData = BufferUtils.createIntBuffer(vertexCount);
        for (int j = 0; j < faceCount; ++j) {
            AIFace face = facesBuffer.get(j);
            if (face.mNumIndices() != 3) {
                throw new IllegalStateException("AIFace.mNumIndices() != 3");
            }
            elementArrayBufferData.put(face.mIndices());
        }
        elementArrayBufferData.flip();
        return elementArrayBufferData;
    }

    /**
     * Computes the mesh's axis alligned bounding box and it's furthest vertex
     * distance.
     *
     * @param mesh mesh
     */
    private void computeFrustumCullingData(@NotNull AIMesh mesh) {
        float max = 0;
        Vector3f aabbMax = new Vector3f();
        Vector3f aabbMin = new Vector3f();
        Vector3f currentVec = new Vector3f();

        for (int i = 0; i < mesh.mVertices().limit(); i++) {
            currentVec.set(mesh.mVertices().get(i).x(),
                    mesh.mVertices().get(i).y(),
                    mesh.mVertices().get(i).z());
            //furthest vertex distance
            if (max < currentVec.length()) {
                max = currentVec.length();
            }
            //aabb
            for (int j = 0; j < 3; j++) {
                if (currentVec.get(j) < aabbMin.get(j)) {
                    aabbMin.setComponent(j, currentVec.get(j));
                }
                if (currentVec.get(j) > aabbMax.get(j)) {
                    aabbMax.setComponent(j, currentVec.get(j));
                }
            }
        }

        this.aabbMin.set(aabbMin);
        this.aabbMax.set(aabbMax);
        furthestVertexDistance = max;
    }

    /**
     * Loads the mesh's data from file to the RAM. It doesn't compute AABB and
     * furthest vertex distance again.
     */
    private void hddToRam() {
        AIMesh mesh = AIMesh.create(getSceneAssimp(getPath()).mMeshes().get(elementIndex));
        hddToRam(mesh);
    }

    /**
     * Loads the mesh's data from the given parameter to the RAM. It doesn't
     * compute AABB and furthest vertex distance again.
     *
     * @param mesh mesh
     */
    private void hddToRam(@NotNull AIMesh mesh) {
        indices = computeIndicesBuffer(mesh);
        position = mesh.mVertices();
        uv = mesh.mTextureCoords(0);
        normal = mesh.mNormals();
        tangent = mesh.mTangents();

        meta.setState(ResourceState.RAM);
    }

    /**
     * Loads the mesh's data from the RAM to the VRAM. It may cause errors if
     * the data isn't in the RAM.
     */
    private void ramToVram() {
        vao = new Vao();
        vao.bindVao();

        vao.createVbo("position");
        vao.bindAndAddData("position", 0, 3, position, false);

        vao.createVbo("uv");
        vao.bindAndAddData("uv", 1, 3, uv, false);

        vao.createVbo("normal");
        vao.bindAndAddData("normal", 2, 3, normal, false);

        vao.createVbo("tangent");
        vao.bindAndAddData("tangent", 3, 3, tangent, false);

        vao.createEbo();
        vao.bindEbo();
        vao.addIndices(indices, false);

        vao.unbindVao();

        meta.setState(ResourceState.VRAM);
    }

    /**
     * Removes the mesh's data from the VRAM. It may cause errors if the data
     * isn't in the VRAM.
     */
    private void vramToRam() {
        vao.release();
        vao = null;

        meta.setState(ResourceState.RAM);
    }

    /**
     * Removes the mesh's data from the RAM. It may cause errors if the data
     * isn't in the RAM.
     */
    private void ramToHdd() {
        position = null;
        uv = null;
        normal = null;
        tangent = null;
        indices = null;

        meta.setState(ResourceState.HDD);
    }

    //
    //opengl related------------------------------------------------------------
    //
    @Override
    public void beforeDraw() {
        if (getState() == ResourceState.VRAM) {
            vao.bindVao();
        }
    }

    @Override
    public void draw() {
        if (getState() != ResourceState.VRAM) {
            if (getState() == ResourceState.HDD) {
                hddToRam();
            }
            ramToVram();
            vao.bindVao();
        }
        GL11.glDrawElements(GL11.GL_TRIANGLES, getVertexCount(), GL11.GL_UNSIGNED_INT, 0);
        meta.setLastActiveToNow();
    }

    @Override
    public void afterDraw() {
        if (getState() == ResourceState.VRAM) {
            vao.unbindVao();
        }
    }

    //
    //data store----------------------------------------------------------------
    //
    /**
     * Returns the VRAM time limit. If the elapsed time since this mesh's last
     * use is higher than this value and the mesh's data store policy is RAM or
     * HDD, the mesh's data may be removed from VRAM. Later if you want to
     * render this mesh, it'll automatically load the data from file again.
     *
     * @return VRAM time limit (in miliseconds)
     */
    public long getVramTimeLimit() {
        return meta.getVramTimeLimit();
    }

    /**
     * Sets the VRAM time limit to the given value. If the elapsed time since
     * this mesh's last use is higher than this value and the mesh's data store
     * policy is RAM or HDD, the mesh's data may be removed from VRAM. Later if
     * you want to render this mesh, it'll automatically load the data from file
     * again.
     *
     * @param vramTimeLimit VRAM time limit (in miliseconds)
     */
    public void setVramTimeLimit(long vramTimeLimit) {
        meta.setVramTimeLimit(vramTimeLimit);
    }

    /**
     * Returns the RAM time limit. If the elapsed time since this mesh's last
     * use is higher than this value and the mesh's data store policy is HDD,
     * the mesh's data may be removed from VRAM or even from RAM. Later if you
     * want to render this mesh, it'll automatically load the data from file
     * again.
     *
     * @return RAM time limit (in miliseconds)
     */
    public long getRamTimeLimit() {
        return meta.getRamTimeLimit();
    }

    /**
     * Sets the RAM time limit to the given value. If the elapsed time since
     * this mesh's last use is higher than this value and the mesh's data store
     * policy is HDD, the mesh's data may be removed from VRAM or even from RAM.
     * Later if you want to render this mesh, it'll automatically load the data
     * from file again.
     *
     * @param ramTimeLimit RAM time limit (in miliseconds)
     */
    public void setRamTimeLimit(long ramTimeLimit) {
        meta.setRamTimeLimit(ramTimeLimit);
    }

    /**
     * Returns the mesh's state. It determines where the mesh is currently
     * stored.
     *
     * @return the mesh's state
     */
    @NotNull
    public ResourceState getState() {
        return meta.getState();
    }

    /**
     * Returns the mesh's data store policy. VRAM means that the mesh's data
     * will be stored in VRAM. RAM means that the mesh's data may be removed
     * from VRAM to RAM if it's rarely used. HDD means that the mesh's data may
     * be removed from VRAM or even from RAM if it's rarely used. Later if you
     * want to render this mesh, it'll automatically load the data from file
     * again.
     *
     * @return the mesh's data store policy
     */
    @NotNull
    public ResourceState getDataStorePolicy() {
        return meta.getDataStorePolicy();
    }

    /**
     * Sets the mesh's data store policy to the given value. VRAM means that the
     * mesh's data will be stored in VRAM. RAM means that the mesh's data may be
     * removed from VRAM to RAM if it's rarely rendered. HDD means that the
     * mesh's data may be removed from VRAM or even from RAM if it's rarely
     * rendered. Later if you want to render this mesh, it'll automatically load
     * the data from file again.
     *
     * @param minState data store policy
     *
     * @throws NullPointerException minState can't be null
     */
    public void setDataStorePolicy(@NotNull ResourceState minState) {
        if (minState == null) {
            throw new NullPointerException();
        }
        meta.setDataStorePolicy(minState);

        if (minState != ResourceState.HDD && getState() == ResourceState.HDD) {
            hddToRam();
        }
        if (minState == ResourceState.VRAM && getState() != ResourceState.VRAM) {
            ramToVram();
        }
    }

    /**
     * Returns the time when the mesh last time used.
     *
     * @return the time when the mesh last time used (in miliseconds)
     */
    public long getLastActive() {
        return meta.getLastActive();
    }

    @Override
    public void update() {
        long elapsedTime = System.currentTimeMillis() - getLastActive();
        if (elapsedTime > getVramTimeLimit() && getDataStorePolicy() != ResourceState.VRAM && getState() != ResourceState.HDD) {
            if (getState() == ResourceState.VRAM) {
                vramToRam();
            }
            if (elapsedTime > getRamTimeLimit() && getDataStorePolicy() == ResourceState.HDD) {
                ramToHdd();
            }
        }
    }

    @Override
    public void release() {
        if (getState() == ResourceState.VRAM) {
            vramToRam();
        }
        if (getState() == ResourceState.RAM) {
            ramToHdd();
        }
    }

    //
    //misc----------------------------------------------------------------------
    //
    /**
     * Returns the loaded model's path.
     *
     * @return the loaded model's path
     */
    @NotNull
    public String getPath() {
        return meta.getPath();
    }

    /**
     * Returns the mesh's index in the loaded model.
     *
     * @return the mesh's index in the loaded model
     */
    public int getIndex() {
        return elementIndex;
    }

    @Override
    public int getDataSize() {
        return meta.getDataSize();
    }

    @Override
    public int getVertexCount() {
        return vertexCount;
    }

    @Override
    public int getFaceCount() {
        return faceCount;
    }

    @Override
    public boolean isUsable() {
        return true;
    }

    @Override
    public float getFurthestVertexDistance() {
        return furthestVertexDistance;
    }

    @NotNull @ReadOnly
    @Override
    public Vector3f getAabbMax() {
        return new Vector3f(aabbMax);
    }

    @NotNull @ReadOnly
    @Override
    public Vector3f getAabbMin() {
        return new Vector3f(aabbMin);
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 41 * hash + this.elementIndex;
        hash = 41 * hash + getPath().hashCode();
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final StaticMesh other = (StaticMesh) obj;
        if (this.elementIndex != other.elementIndex) {
            return false;
        }
        if (!this.getPath().equals(other.getPath())) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "StaticMesh{" + "vao=" + vao + ", vertexCount=" + vertexCount
                + ", faceCount=" + faceCount
                + ", furthestVertexDistance=" + furthestVertexDistance
                + ", aabbMin=" + aabbMin + ", aabbMax=" + aabbMax
                + ", elementIndex=" + elementIndex + ", position=" + position
                + ", uv=" + uv + ", normal=" + normal + ", tangent=" + tangent
                + ", indices=" + indices + ", meta=" + meta + '}';
    }

}