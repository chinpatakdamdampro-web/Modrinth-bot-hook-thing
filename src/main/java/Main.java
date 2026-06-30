import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

public class Main {
    private static final String STATE_FILE = "state.json";
    private static final String[] MOD_SLUGS = {"vulgars-pvp-bot", "vulgars-orbital-strike-mod"};
    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/";
    private static final String USER_AGENT = "vulgar-webhook-notifier/1.1";
    
    // Official Modrinth Logo for the Webhook and Embed
    private static final String MODRINTH_LOGO = "https://docs.modrinth.com/img/logo.png";
    private static final int MODRINTH_GREEN = 44892; // Hex #00AF5C

    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        String webhookUrl = System.getenv("WEBHOOK_URL");
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            System.err.println("Error: WEBHOOK_URL environment variable is missing.");
            System.exit(1);
        }

        Map<String, String> state = loadState();
        boolean stateUpdated = false;

        for (String slug : MOD_SLUGS) {
            System.out.println("Checking " + slug + "...");
            
            JsonObject projectInfo = fetchJson(MODRINTH_API + slug);
            if (projectInfo == null) continue;

            JsonArray versions = fetchJsonArray(MODRINTH_API + slug + "/version");
            if (versions == null || versions.isEmpty()) continue;

            JsonObject latestVersion = versions.get(0).getAsJsonObject();
            String versionNumber = latestVersion.get("version_number").getAsString();
            String savedVersion = state.get(slug);

            if (!versionNumber.equals(savedVersion)) {
                System.out.println("New version found for " + slug + ": " + versionNumber);
                
                sendDiscordWebhook(webhookUrl, projectInfo, latestVersion, slug);
                
                state.put(slug, versionNumber);
                stateUpdated = true;
                
                Thread.sleep(1000); 
            } else {
                System.out.println("No updates for " + slug + ".");
            }
        }

        if (stateUpdated) {
            saveState(state);
            System.out.println("state.json updated.");
        }
    }

    private static void sendDiscordWebhook(String webhookUrl, JsonObject project, JsonObject version, String slug) throws IOException {
        // Project Info
        String projectTitle = project.has("title") ? project.get("title").getAsString() : slug;
        String iconUrl = project.has("icon_url") && !project.get("icon_url").isJsonNull() ? project.get("icon_url").getAsString() : null;
        String totalDownloads = project.has("downloads") ? project.get("downloads").getAsString() : "0";
        
        // Version Info
        String versionNumber = version.get("version_number").getAsString();
        String versionName = version.has("name") && !version.get("name").isJsonNull() ? version.get("name").getAsString() : versionNumber;
        String versionType = formatVersionType(version.get("version_type").getAsString());
        String publishedTimestamp = version.get("date_published").getAsString();
        
        String mcVersions = joinJsonArray(version.getAsJsonArray("game_versions"));
        String loaders = joinJsonArray(version.getAsJsonArray("loaders"));
        
        String changelog = version.has("changelog") && !version.get("changelog").isJsonNull() 
                ? version.get("changelog").getAsString() : "No changelog provided.";
        if (changelog.length() > 2048) {
            changelog = changelog.substring(0, 2045) + "...";
        }

        // Extract File Info (URL and Size)
        String downloadUrl = "https://modrinth.com/mod/" + slug + "/versions";
        String fileSize = "Unknown";
        JsonArray files = version.getAsJsonArray("files");
        
        if (files != null && !files.isEmpty()) {
            JsonObject primaryFile = files.get(0).getAsJsonObject();
            if (primaryFile.has("url")) downloadUrl = primaryFile.get("url").getAsString();
            if (primaryFile.has("size")) {
                long bytes = primaryFile.get("size").getAsLong();
                fileSize = formatFileSize(bytes);
            }
        }

        // --- Build Discord Payload ---
        JsonObject payload = new JsonObject();
        
        // Overrides the Webhook's default name and picture
        payload.addProperty("username", "Vulgar Update Checker");
        payload.addProperty("avatar_url", MODRINTH_LOGO);
        
        // Main text message above the embed
        payload.addProperty("content", "📢 **New update available!** Download here:\n" + downloadUrl);

        JsonObject embed = new JsonObject();
        
        // Author Block
        JsonObject author = new JsonObject();
        author.addProperty("name", "Modrinth — New Version Released");
        author.addProperty("icon_url", MODRINTH_LOGO);
        embed.add("author", author);

        // Title and Link
        embed.addProperty("title", projectTitle + " — " + versionName);
        embed.addProperty("url", "https://modrinth.com/mod/" + slug);
        embed.addProperty("description", changelog);
        embed.addProperty("color", MODRINTH_GREEN);

        // Thumbnail (Mod Icon)
        if (iconUrl != null) {
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", iconUrl);
            embed.add("thumbnail", thumbnail);
        }

        // Fields (Inline set to false to stack vertically like the image)
        JsonArray fields = new JsonArray();
        fields.add(createField("🏷️ Version Number", versionNumber, false));
        fields.add(createField("📦 Release Type", versionType, false));
        fields.add(createField("⚙️ Loaders", truncateField(loaders), false));
        fields.add(createField("🎮 Game Versions", truncateField(mcVersions), false));
        fields.add(createField("💾 File Size", fileSize, false));
        fields.add(createField("⬇️ Downloads (Total)", totalDownloads, false));
        embed.add("fields", fields);

        // Footer and Timestamp
        JsonObject footer = new JsonObject();
        footer.addProperty("text", projectTitle + " • modrinth.com/mod/" + slug);
        footer.addProperty("icon_url", MODRINTH_LOGO);
        embed.add("footer", footer);
        embed.addProperty("timestamp", publishedTimestamp); // Discord automatically formats this to local time

        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);

        // Send Request
        RequestBody body = RequestBody.create(gson.toJson(payload), MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(webhookUrl).post(body).build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                System.err.println("Discord webhook failed for " + slug + ": HTTP " + response.code());
            } else {
                System.out.println("Discord webhook sent for " + slug);
            }
        }
    }

    // --- Helper Methods ---

    private static String formatVersionType(String type) {
        if (type == null) return "Unknown";
        String cap = type.substring(0, 1).toUpperCase() + type.substring(1).toLowerCase();
        return switch (type.toLowerCase()) {
            case "release" -> "🟢 " + cap;
            case "beta" -> "🟡 " + cap;
            case "alpha" -> "🔴 " + cap;
            default -> "⚪ " + cap;
        };
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static JsonObject fetchJson(String url) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            return gson.fromJson(response.body().string(), JsonObject.class);
        }
    }

    private static JsonArray fetchJsonArray(String url) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            return gson.fromJson(response.body().string(), JsonArray.class);
        }
    }

    private static Map<String, String> loadState() {
        try {
            Path path = Paths.get(STATE_FILE);
            if (Files.exists(path)) {
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> data = gson.fromJson(Files.readString(path), type);
                return data != null ? data : new HashMap<>();
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not parse state.json. Starting fresh.");
        }
        return new HashMap<>();
    }

    private static void saveState(Map<String, String> state) {
        try {
            Files.writeString(Paths.get(STATE_FILE), gson.toJson(state));
        } catch (IOException e) {
            System.err.println("Error saving state.json: " + e.getMessage());
        }
    }

    private static String joinJsonArray(JsonArray array) {
        if (array == null || array.isEmpty()) return "N/A";
        List<String> list = new ArrayList<>();
        for (JsonElement e : array) {
            list.add(e.getAsString());
        }
        return String.join(", ", list);
    }

    private static JsonObject createField(String name, String value, boolean inline) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value == null || value.isEmpty() ? "N/A" : value);
        field.addProperty("inline", inline);
        return field;
    }

    private static String truncateField(String value) {
        return value.length() > 1024 ? value.substring(0, 1021) + "..." : value;
    }
}
