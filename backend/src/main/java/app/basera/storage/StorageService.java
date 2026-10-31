package app.basera.storage;

import java.util.Optional;

/** Binary storage abstraction. The shipped implementation is local disk (a volume in Docker/Kubernetes). */
public interface StorageService {

    record Stored(byte[] data, String contentType) {}

    void put(String key, byte[] data);

    Optional<Stored> get(String key);

    void delete(String key);
}
