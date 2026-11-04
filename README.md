# zeli-news-enc (Yejin Co., Ltd. Private Encrypted Distribution)

- **Modules**: `backend_cast` (`:5171`) + `backend_press` (`:8091`)
- **Hard Tethering**: 100% tethered to `https://korea.stream` (`0 0 21 * * *` KST daily lease + real-time HMAC-SHA256 webhook). No SerpAPI fallback on news.
- **Chunked Encryption (No Git LFS)**:
  - Algorithm: `AES-256-CBC` (`-pbkdf2 -iter 100000 -salt`)
  - Chunk size: `<= 15MB` (`split -b 15M -d -a 2`)
  - Runtime: Decrypted directly into RAM (`/dev/shm/zeliai_news_runtime`), with Node.js plaintext `.js` files immediately deleted from RAM once loaded into V8 memory.

## Usage
```bash
./assemble_and_run.sh
```
