# 12 — Catalog Swagger Error Codes, Optimistic Locking & PIT Mutation Testing (#187)

**Author:** Nedelcu Liviu (`@NliviuN`)  
**Area:** Catalog, Common Error Handling & Storage (`com.campus.catalog`, `com.campus.common.error`, `com.campus.storage`)  
**Context:** Issue #187 & W7–W8 `STALE_VERSION` milestone  

---

## Part 1: In simple terms (pe înțelesul tuturor)

### Ce am făcut și de ce contează?
1. **Documentație completă în Swagger pentru codurile de eroare:** Toate cele 13 endpoint-uri din catalog (echipamente, categorii, săli de laborator) afișează acum explicit în interfața Swagger ce coduri de eroare pot returna (de exemplu `404 ASSET_NOT_FOUND`, `409 SERIAL_NUMBER_TAKEN`, `409 CATEGORY_IN_USE`), alături de exemple clare pentru crearea unui echipament.
2. **Protecție la editare simultană (`STALE_VERSION`):** Am adăugat un test de bază de date care verifică ce se întâmplă când doi administratori deschid același echipament în același timp și încearcă amândoi să îl modifice. Primul reușește, iar al doilea primește eroarea `409 STALE_VERSION` în loc să suprascrie în tăcere modificările primului administrator.
3. **Verificarea calității testelor cu PIT Mutation Testing:** Un procent mare de acoperire a codului (line coverage) nu garantează că testele chiar verifică rezultatul corect. Unelta **PIT (`pitest`)** introduce intenționat mici defecte (mutații) în cod și verifică dacă vreun test pică. Am adăugat teste pentru toate cazurile-limită și operațiile de modificare/ștergere identificate în **Issue #187**, eliminând toți mutanții din `catalog`, `common` și `StorageValidation`.

---

## Part 2: Technical details

### 1. OpenAPI / Swagger Error Responses (`catalog/web`)

| Controller | Endpoints Updated | Documented Error Codes |
|---|---|---|
| [`AssetController`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/catalog/web/AssetController.java) | `GET /assets`, `GET /assets/{id}`, `POST /admin/assets`, `PUT /admin/assets/{id}`, `POST /admin/assets/{id}/retire` | `400 INVALID_PARAMETER`, `400 VALIDATION_FAILED`, `404 ASSET_NOT_FOUND`, `404 CATEGORY_NOT_FOUND`, `404 LAB_ROOM_NOT_FOUND`, `409 SERIAL_NUMBER_TAKEN`, `409 ASSET_RETIRED`, `409 STALE_VERSION` |
| [`CategoryController`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/catalog/web/CategoryController.java) | `GET /categories`, `POST /admin/categories`, `PUT /admin/categories/{id}`, `DELETE /admin/categories/{id}` | `400 VALIDATION_FAILED`, `400 INVALID_PARAMETER`, `404 CATEGORY_NOT_FOUND`, `409 CATEGORY_NAME_TAKEN`, `409 CATEGORY_IN_USE` |
| [`LabRoomController`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/catalog/web/LabRoomController.java) | `GET /lab-rooms`, `POST /admin/lab-rooms`, `PUT /admin/lab-rooms/{id}`, `DELETE /admin/lab-rooms/{id}` | `400 VALIDATION_FAILED`, `400 INVALID_PARAMETER`, `404 LAB_ROOM_NOT_FOUND`, `409 LAB_ROOM_CODE_TAKEN`, `409 LAB_ROOM_IN_USE` |
| [`CreateAssetRequest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/catalog/web/CreateAssetRequest.java) | `@Schema(example = ...)` on all fields | Pre-populates Swagger UI payload for quick testing |

### 2. Optimistic Locking (`STALE_VERSION`) Verification
- În [`AssetRepositoryTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/catalog/repository/AssetRepositoryTest.java), testul `rejectsConcurrentStaleVersionUpdateOnAsset()` încarcă un `Asset` (`@Version = 0`), îl detașează din contextul JPA, salvează o modificare concurentă care incrementează versiunea la `1`, apoi încearcă salvarea copiei vechi (`version = 0`) și verifică aruncarea `ObjectOptimisticLockingFailureException`.
- În [`GlobalExceptionHandlerTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/common/error/GlobalExceptionHandlerTest.java), testul `mapsOptimisticLockingFailureTo409StaleVersion()` verifică maparea excepției la `409 Conflict` cu `code = "STALE_VERSION"`.

### 3. PIT Mutation Survivors Eliminated

| Module / Class | Mutants Killed & New Tests Added |
|---|---|
| [`AssetServiceTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/catalog/service/AssetServiceTest.java) | Căile complete `update` (modificare câmpuri, audit, `404 ASSET_NOT_FOUND`, `404 LAB_ROOM_NOT_FOUND`), `retire` (`404`), limitele de paginare (`size == 1`, `size == 100`, `page * size == Integer.MAX_VALUE`), lungimea exactă de 100 caractere a seriei și predicatul `excludeRetired` (student vs admin). |
| [`CategoryServiceTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/catalog/service/CategoryServiceTest.java) & [`LabRoomServiceTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/catalog/service/LabRoomServiceTest.java) | `listAll()`, `update()` (happy path, audit, răspuns, nume/cod duplicat `409`, race condition `DataIntegrityViolationException`, limita de 20 caractere pe codul sălii), `LabRoomService.delete()` (happy path, `flush()` FK violation -> `409 LAB_ROOM_IN_USE`, audit). |
| [`AssetControllerTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/catalog/web/AssetControllerTest.java), [`CategoryControllerTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/catalog/web/CategoryControllerTest.java), [`LabRoomControllerTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/catalog/web/LabRoomControllerTest.java) | Teste `@WebMvcTest` pentru `PUT /api/v1/admin/assets/{id}`, `PUT /api/v1/admin/categories/{id}` și `PUT /api/v1/admin/lab-rooms/{id}` (`200 OK` + verificare corp JSON). |
| [`GlobalExceptionHandler`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/common/error/GlobalExceptionHandler.java) & [`GlobalExceptionHandlerTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/common/error/GlobalExceptionHandlerTest.java) | Teste pentru `handleInvalidSortProperty` (`400`), `handleStaleVersion` (`409`), `handleMaxUploadSize` (`413`), `handleMalformedRequest` (`400`) și eliminarea apelului redundant `setTitle` (deja rezolvat automat de `ProblemDetail.forStatusAndDetail`). |
| [`StorageValidation`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/storage/service/StorageValidation.java) & [`LocalStorageServiceTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/storage/service/LocalStorageServiceTest.java) | Teste pe valorile de graniță: `declaredSize == 0`, `declaredSize == MAX_PHOTO_BYTES`, stream de exact `MAX_PHOTO_BYTES`, dimensiuni minime exacte pentru magic bytes (3 octeți JPEG, 8 octeți PNG, 12 octeți WebP) și simplificarea `normalizeContentType` cu `split(";", 2)[0]`. |
