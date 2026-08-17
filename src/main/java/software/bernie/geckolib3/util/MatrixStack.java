package software.bernie.geckolib3.util;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;

import org.lwjgl.util.vector.Quaternion;

import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;

/**
 * Matrix stack backed by pre-allocated arrays: push/pop reuse matrix objects
 * instead of allocating new ones per call. Not thread-safe (rendering runs on
 * the main thread only). Matrices returned by {@link #getModelMatrix()} /
 * {@link #getNormalMatrix()} are reused by later pushes — do not retain them
 * across push/pop calls.
 */
public class MatrixStack {
	private static final int DEFAULT_CAPACITY = 64;

	private Matrix4f[] model;
	private Matrix3f[] normal;
	private int depth = 0;

	// Reusable temporaries (single-threaded rendering)
	private final Matrix4f tempModelMatrix = new Matrix4f();
	private final Matrix3f tempNormalMatrix = new Matrix3f();
	private final Matrix4f tempRotModelMatrix = new Matrix4f();
	private final Matrix3f tempRotNormalMatrix = new Matrix3f();
	private final Vector3f tempVec = new Vector3f();

	public MatrixStack() {
		this(DEFAULT_CAPACITY);
	}

	public MatrixStack(int capacity) {
		this.model = new Matrix4f[capacity];
		this.normal = new Matrix3f[capacity];
		for (int i = 0; i < capacity; i++) {
			this.model[i] = new Matrix4f();
			this.normal[i] = new Matrix3f();
		}
		this.model[0].setIdentity();
		this.normal[0].setIdentity();
	}

	public Matrix4f getModelMatrix() {
		return this.model[this.depth];
	}

	public Matrix3f getNormalMatrix() {
		return this.normal[this.depth];
	}

	public void push() {
		if (++this.depth >= this.model.length) {
			this.grow();
		}
		this.model[this.depth].set(this.model[this.depth - 1]);
		this.normal[this.depth].set(this.normal[this.depth - 1]);
	}

	public void pop() {
		if (this.depth == 0) {
			throw new IllegalStateException("A one level stack can't be popped!");
		}

		this.depth--;
	}

	private void grow() {
		int capacity = this.model.length * 2;
		Matrix4f[] newModel = new Matrix4f[capacity];
		Matrix3f[] newNormal = new Matrix3f[capacity];
		System.arraycopy(this.model, 0, newModel, 0, this.model.length);
		System.arraycopy(this.normal, 0, newNormal, 0, this.normal.length);
		for (int i = this.model.length; i < capacity; i++) {
			newModel[i] = new Matrix4f();
			newNormal[i] = new Matrix3f();
		}
		this.model = newModel;
		this.normal = newNormal;
	}

	/* Translate */

	public void translate(float x, float y, float z) {
		this.tempVec.set(x, y, z);
		this.translate(this.tempVec);
	}

	public void translate(Vector3f vec) {
		this.tempModelMatrix.setIdentity();
		this.tempModelMatrix.setTranslation(vec);

		this.model[this.depth].mul(this.tempModelMatrix);
	}

	public void moveToPivot(GeoCube cube) {
		Vector3f pivot = cube.pivot;
		this.translate(pivot.getX() / 16, pivot.getY() / 16, pivot.getZ() / 16);
	}

	public void moveBackFromPivot(GeoCube cube) {
		Vector3f pivot = cube.pivot;
		this.translate(-pivot.getX() / 16, -pivot.getY() / 16, -pivot.getZ() / 16);
	}

	public void moveToPivot(GeoBone bone) {
		this.translate(bone.rotationPointX / 16, bone.rotationPointY / 16, bone.rotationPointZ / 16);
	}

	public void moveBackFromPivot(GeoBone bone) {
		this.translate(-bone.rotationPointX / 16, -bone.rotationPointY / 16, -bone.rotationPointZ / 16);
	}

	public void translate(GeoBone bone) {
		this.translate(-bone.getPositionX() / 16, bone.getPositionY() / 16, bone.getPositionZ() / 16);
	}

	/* Scale */

	public void scale(float x, float y, float z) {
		this.tempModelMatrix.setIdentity();
		this.tempModelMatrix.setM00(x);
		this.tempModelMatrix.setM11(y);
		this.tempModelMatrix.setM22(z);

		this.model[this.depth].mul(this.tempModelMatrix);

		if (x < 0 || y < 0 || z < 0) {
			this.tempNormalMatrix.setIdentity();
			this.tempNormalMatrix.setM00(x < 0 ? -1 : 1);
			this.tempNormalMatrix.setM11(y < 0 ? -1 : 1);
			this.tempNormalMatrix.setM22(z < 0 ? -1 : 1);

			this.normal[this.depth].mul(this.tempNormalMatrix);
		}
	}

	public void scale(GeoBone bone) {
		this.scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
	}

	/* Rotate */

	public void rotateX(float radian) {
		this.tempModelMatrix.setIdentity();
		this.tempModelMatrix.rotX(radian);

		this.tempNormalMatrix.setIdentity();
		this.tempNormalMatrix.rotX(radian);

		this.model[this.depth].mul(this.tempModelMatrix);
		this.normal[this.depth].mul(this.tempNormalMatrix);
	}

	public void rotateY(float radian) {
		this.tempModelMatrix.setIdentity();
		this.tempModelMatrix.rotY(radian);

		this.tempNormalMatrix.setIdentity();
		this.tempNormalMatrix.rotY(radian);

		this.model[this.depth].mul(this.tempModelMatrix);
		this.normal[this.depth].mul(this.tempNormalMatrix);
	}

	public void rotateZ(float radian) {
		this.tempModelMatrix.setIdentity();
		this.tempModelMatrix.rotZ(radian);

		this.tempNormalMatrix.setIdentity();
		this.tempNormalMatrix.rotZ(radian);

		this.model[this.depth].mul(this.tempModelMatrix);
		this.normal[this.depth].mul(this.tempNormalMatrix);
	}

	public void rotate(GeoBone bone) {
		if (bone.getRotationZ() != 0.0F) {
			this.rotateZ(bone.getRotationZ());
		}

		if (bone.getRotationY() != 0.0F) {
			this.rotateY(bone.getRotationY());
		}

		if (bone.getRotationX() != 0.0F) {
			this.rotateX(bone.getRotationX());
		}
	}

	public void rotate(GeoCube bone) {
		Vector3f rotation = bone.rotation;

		this.tempModelMatrix.setIdentity();
		this.tempRotModelMatrix.rotZ(rotation.getZ());
		this.tempModelMatrix.mul(this.tempRotModelMatrix);

		this.tempRotModelMatrix.rotY(rotation.getY());
		this.tempModelMatrix.mul(this.tempRotModelMatrix);

		this.tempRotModelMatrix.rotX(rotation.getX());
		this.tempModelMatrix.mul(this.tempRotModelMatrix);

		this.tempNormalMatrix.setIdentity();
		this.tempRotNormalMatrix.rotZ(rotation.getZ());
		this.tempNormalMatrix.mul(this.tempRotNormalMatrix);

		this.tempRotNormalMatrix.rotY(rotation.getY());
		this.tempNormalMatrix.mul(this.tempRotNormalMatrix);

		this.tempRotNormalMatrix.rotX(rotation.getX());
		this.tempNormalMatrix.mul(this.tempRotNormalMatrix);

		this.model[this.depth].mul(this.tempModelMatrix);
		this.normal[this.depth].mul(this.tempNormalMatrix);
	}

	@SuppressWarnings("unused")
	private Quaternion fromAngles(float x, float y, float z) {
		float sx = (float) Math.sin(0.5F * x);
		float cx = (float) Math.cos(0.5F * x);
		float sy = (float) Math.sin(0.5F * y);
		float cy = (float) Math.cos(0.5F * y);
		float sz = (float) Math.sin(0.5F * z);
		float cz = (float) Math.cos(0.5F * z);

		float ox = sx * cy * cz + cx * sy * sz;
		float oy = cx * sy * cz - sx * cy * sz;
		float oz = sx * sy * cz + cx * cy * sz;
		float ow = cx * cy * cz - sx * sy * sz;

		return new Quaternion(ox, oy, oz, ow);
	}
}
