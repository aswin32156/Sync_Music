# 🎵 MusicSync - Real-Time Collaborative Music Player

A real-time synchronized music listening platform where friends can create rooms, add songs from millions of tracks, and listen together in perfect sync.

## ✨ Features

- **🎧 Real-Time Sync** - WebSocket-based instant synchronization across all listeners
- **🎵 Millions of Songs** - Search and play songs via YouTube Music APIs
- **👥 Room System** - Create password-protected or public rooms
- **💬 Live Chat** - Chat while listening together
- **👫 Friends System** - Add friends, see who's online, join their rooms
- **🎮 Host Controls** - Room creator controls playback for everyone
- **📱 Responsive** - Works on desktop, tablet, and mobile

## 🚀 Quick Start

### Prerequisites
- Java 21
- Maven 3.9+

### Run Locally

```bash
# Clone the repository
git clone https://github.com/aswin32156/Sync_Music.git
cd Sync_Music

# Run the application
./mvnw spring-boot:run

# Access at http://localhost:8080
```

## 🌐 Deploy to Render

1. **Push to GitHub:**
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git remote add origin <your-github-repo-url>
   git push -u origin main
   ```

2. **Deploy on Render:**
   - Go to [render.com](https://render.com)
   - Click "New +" → "Web Service"
   - Connect your GitHub repository
   - Render will auto-detect `render.yaml`
   - Click "Create Web Service"
   - Wait 5-10 minutes for deployment

3. **Access Your App:**
   - Your app will be live at: `https://musicsync-xxxx.onrender.com`

## 🎮 How to Use

### Create a Room
1. Click "Create Room"
2. Enter your name
3. Set room name (optional)
4. Set password (optional for private rooms)
5. Share room code with friends!

### Join a Room
1. Click "Join Room"
2. Enter your name
3. Enter the 6-character room code
4. Enter password if required

### Add Friends
1. In the room, click "Friends" in sidebar
2. Search for username
3. Click to send friend request
4. Friends can see when you're online and join your rooms!

### Search & Play Music
1. Click "Search All" tab
2. Type any song, artist, or language
3. Click + to add songs to queue
4. Only host can control playback (play/pause/skip)

## 🛠️ Tech Stack

- **Backend:** Spring Boot 3.2.3, Java 21
- **WebSocket:** STOMP over SockJS
- **APIs:** Official JioSaavn partner API support when credentials are available, YouTube Data API v3 (optional)
- **Frontend:** Vanilla JavaScript, HTML5 Audio API
- **Deployment:** Docker, Render.com

## 📝 API Endpoints

### Rooms
- `POST /api/rooms/create` - Create a new room
- `POST /api/rooms/join` - Join existing room
- `GET /api/rooms/{code}` - Get room state

### Friends
- `POST /api/friends/request` - Send friend request
- `POST /api/friends/request/{id}/accept` - Accept request
- `GET /api/friends/{userId}` - Get friends list
- `GET /api/friends/search?q={query}` - Search users

### Music
- `GET /api/music/search/external?q={query}` - Search songs
- `GET /api/music/sources` - Get available music sources

## 🔧 Configuration

Edit `src/main/resources/application.properties`:

```properties
server.port=8080
server.address=0.0.0.0

# Optional: Add YouTube API key for YouTube Music metadata search
youtube.api-key=your_youtube_api_key

# Optional: Official JioSaavn partner API settings
jiosaavn.api-base-url=https://your-jiosaavn-partner-api.example.com
jiosaavn.api-key=your_jiosaavn_api_key

# OR set environment variable (recommended)
YOUTUBE_API_KEY=your_youtube_api_key
JIOSAAVN_API_BASE_URL=https://your-jiosaavn-partner-api.example.com
JIOSAAVN_API_KEY=your_jiosaavn_api_key
```

### YouTube Setup (Optional, Improves Reliability)

1. Create a project in Google Cloud Console
2. Enable **YouTube Data API v3**
3. Create an API key
4. Set `YOUTUBE_API_KEY`
5. Restart the app, then verify: `GET /api/music/sources` returns `"youtubeApiConfigured": true`

Without an API key, MusicSync still searches YouTube using a web metadata fallback.

### JioSaavn Partner Setup

1. Request official partner/API access from JioSaavn.
2. Set `JIOSAAVN_API_BASE_URL` and `JIOSAAVN_API_KEY` after approval.
3. Restart the app.
4. The official JioSaavn client will take over automatically when both values are present.

If partner access is not available, the app will continue to work with YouTube-first fallback behavior.

## 📱 Network Access

- **Localhost:** `http://localhost:8080`
- **Same WiFi:** `http://YOUR_IP:8080`
- **Internet:** Deploy to Render or use ngrok

## 🤝 Contributing

Contributions are welcome! Feel free to submit issues and pull requests.

## 📄 License

MIT License

## 🎉 Credits

- JioSaavn API for music streaming
- YouTube Data API v3 for YouTube Music metadata
- Font Awesome for icons

---

Made with ❤️ for music lovers everywhere
