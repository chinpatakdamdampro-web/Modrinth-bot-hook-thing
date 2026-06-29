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
    private static final String USER_AGENT = "custom-webhook-notifier/1.0";
    
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
            
            // 1. Fetch project info (for Title and Icon)
            JsonObject projectInfo = fetchJson(MODRINTH_API + slug);
            if (projectInfo == null) continue;

            // 2. Fetch versions array (Index 0 is the newest release)
            JsonArray versions = fetchJsonArray(MODRINTH_API + slug + "/version");
            if (versions == null || versions.isEmpty()) continue;

            JsonObject latestVersion = versions.get(0).getAsJsonObject();
            String versionNumber = latestVersion.get("version_number").getAsString();
            String savedVersion = state.get(slug);

            // 3. Compare with saved state
            if (!versionNumber.equals(savedVersion)) {
                System.out.println("New version found for " + slug + ": " + versionNumber);
                
                sendDiscordWebhook(webhookUrl, projectInfo, latestVersion, slug);
                
                state.put(slug, versionNumber);
                stateUpdated = true;
                
                // Rate limit protection just in case
                Thread.sleep(1000); 
            } else {
                System.out.println("No updates for " + slug + ".");
            }
        }

        // 4. Save state if any modifications occurred
        if (stateUpdated) {
            saveState(state);
            System.out.println("state.json updated.");
        }
    }

    private static void sendDiscordWebhook(String webhookUrl, JsonObject project, JsonObject version, String slug) throws IOException {
        String title = project.has("title") ? project.get("title").getAsString() : slug;
        String iconUrl = project.has("icon_url") && !project.get("icon_url").isJsonNull() ? project.get("icon_url").getAsString() : null;
        
        String versionNumber = version.get("version_number").getAsString();
        String versionType = version.get("version_type").getAsString();
        String published = version.get("date_published").getAsString().split("T")[0]; // Just the date
        
        String mcVersions = joinJsonArray(version.getAsJsonArray("game_versions"));
        String loaders = joinJsonArray(version.getAsJsonArray("loaders"));
        
        String changelog = version.has("changelog") && !version.get("changelog").isJsonNull() 
                ? version.get("changelog").getAsString() : "No changelog provided.";
        
        // Truncate changelog to stay well under Discord's 4096 character limit for descriptions
        if (changelog.length() > 2048) {
            changelog = changelog.substring(0, 2045) + "...";
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("content", "🚀 New update available for " + title + "!");

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🚀 " + title + " has been updated!");
        embed.addProperty("url", "https://modrinth.com/mod/" + slug);
        embed.addProperty("description", "**Changelog:**\n" + changelog);
        embed.addProperty("color", 5814783); // A nice Discord-friendly color

        if (iconUrl != null) {
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", iconUrl);
            embed.add("thumbnail", thumbnail);
        }

        JsonArray fields = new JsonArray();
        fields.add(createField("Version", versionNumber, true));
        fields.add(createField("Minecraft", truncateField(mcVersions), true));
        fields.add(createField("Loaders", truncateField(loaders), true));
        fields.add(createField("Type", versionType, true));
        fields.add(createField("Published", published, true));
        embed.add("fields", fields);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);

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
        // Discord max limit for field value is 1024 characters
        return value.length() > 1024 ? value.substring(0, 1021) + "..." : value;
    }
}
