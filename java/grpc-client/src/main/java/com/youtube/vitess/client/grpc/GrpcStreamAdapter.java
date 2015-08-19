package com.youtube.vitess.client.grpc;

import com.youtube.vitess.client.StreamIterator;
import com.youtube.vitess.client.VitessException;
import com.youtube.vitess.client.VitessRpcException;

import io.grpc.stub.StreamObserver;

import java.util.NoSuchElementException;

/**
 * GrpcStreamAdapter is an implementation of StreamIterator that allows
 * iteration (with checked exceptions) over results obtained through the
 * gRPC StreamObserver interface.
 *
 * <p>This class is abstract because it needs to be told how to extract the
 * result (e.g. QueryResult) from a given RPC response (e.g. StreamExecuteResponse).
 * Callers must therefore implement getResult() when instantiating this class.
 *
 * @param <V> The type of value sent through the StreamObserver interface.
 * @param <E> The type of value to return through the StreamIterator interface.
 */
abstract class GrpcStreamAdapter<V, E> implements StreamObserver<V>, StreamIterator<E> {
  /**
   * getResult must be implemented to tell the adapter how to convert from
   * the StreamObserver value type (V) to the StreamIterator value type (E).
   * Before converting, getResult() should check for application-level errors
   * in the RPC response and throw VitessException.
   * @param value The RPC response object.
   * @return The result object to pass to the iterator consumer.
   * @throws VitessException For errors originating within the Vitess server.
   */
  abstract E getResult(V value) throws VitessException;

  private E nextValue;
  private Throwable error;
  private boolean completed = false;

  @Override
  public void onValue(V value) {
    synchronized (this) {
      try {
        // Wait until the previous value has been consumed.
        while (this.nextValue != null) {
          // If there's been an error, drain the rest of the stream without blocking.
          if (this.error != null)
            return;

          this.wait();
        }

        this.nextValue = getResult(value);
        this.notifyAll();
      } catch (InterruptedException e) {
        this.onError(e);
      } catch (VitessException e) {
        this.onError(e);
      }
    }
  }

  @Override
  public void onCompleted() {
    synchronized (this) {
      this.completed = true;
      this.notifyAll();
    }
  }

  @Override
  public void onError(Throwable error) {
    synchronized (this) {
      this.error = error;
      this.notifyAll();
    }
  }

  @Override
  public boolean hasNext() throws VitessException, VitessRpcException {
    synchronized (this) {
      try {
        // Wait for a new value to show up.
        while (this.nextValue == null) {
          if (this.completed)
            return false;
          if (this.error != null) {
            if (this.error instanceof VitessException)
              throw (VitessException) this.error;
            else
              throw new VitessRpcException("error in gRPC StreamIterator", this.error);
          }

          this.wait();
        }

        return true;
      } catch (InterruptedException e) {
        this.onError(e);
        throw new VitessRpcException("error in gRPC StreamIterator", e);
      }
    }
  }

  @Override
  public E next() throws NoSuchElementException, VitessException, VitessRpcException {
    synchronized (this) {
      if (this.hasNext()) {
        E value = this.nextValue;
        this.nextValue = null;
        this.notifyAll();
        return value;
      } else {
        throw new NoSuchElementException("stream completed");
      }
    }
  }
}
