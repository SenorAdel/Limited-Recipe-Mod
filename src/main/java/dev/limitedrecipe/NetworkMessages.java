package dev.limitedrecipe;

import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class NetworkMessages {
    public static final int CATALOG_CHUNK_SIZE = 8_000;
    public static final int MAX_CATALOG_CHUNKS = 512;

    private NetworkMessages() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("7");
        registrar.playToClient(RecipeCatalog.TYPE, RecipeCatalog.STREAM_CODEC);
        registrar.playToClient(HiddenRecipeList.TYPE, HiddenRecipeList.STREAM_CODEC);
        registrar.playToServer(SaveRecipeRule.TYPE, SaveRecipeRule.STREAM_CODEC, Networking::saveRule);
        registrar.playToServer(DeleteRecipeRule.TYPE, DeleteRecipeRule.STREAM_CODEC, Networking::deleteRule);
        registrar.playToServer(SetRecipeRuleEnabled.TYPE, SetRecipeRuleEnabled.STREAM_CODEC,
                Networking::setRecipeRuleEnabled);
        registrar.playToServer(RecipeJsonRequest.TYPE, RecipeJsonRequest.STREAM_CODEC, Networking::requestRecipeJson);
        registrar.playToClient(RecipeJsonData.TYPE, RecipeJsonData.STREAM_CODEC);
        registrar.playToServer(SaveRecipeJson.TYPE, SaveRecipeJson.STREAM_CODEC, Networking::saveRecipeJson);
        registrar.playToServer(AccessRulesJsonRequest.TYPE, AccessRulesJsonRequest.STREAM_CODEC,
                Networking::requestAccessRulesJson);
        registrar.playToServer(SaveAccessRulesJson.TYPE, SaveAccessRulesJson.STREAM_CODEC,
                Networking::saveAccessRulesJson);
        registrar.playToClient(AccessRulesJsonData.TYPE, AccessRulesJsonData.STREAM_CODEC);
    }

    public record RecipeJsonRequest(String recipeId) implements CustomPacketPayload {
        public static final Type<RecipeJsonRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
                LimitedRecipeMod.MOD_ID, "recipe_json_request"));
        public static final StreamCodec<ByteBuf, RecipeJsonRequest> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RecipeJsonRequest::recipeId, RecipeJsonRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RecipeJsonData(String recipeId, String json, boolean hasOverride, String message)
            implements CustomPacketPayload {
        public static final Type<RecipeJsonData> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
                LimitedRecipeMod.MOD_ID, "recipe_json_data"));
        public static final StreamCodec<ByteBuf, RecipeJsonData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RecipeJsonData::recipeId,
                ByteBufCodecs.STRING_UTF8, RecipeJsonData::json,
                ByteBufCodecs.BOOL, RecipeJsonData::hasOverride,
                ByteBufCodecs.STRING_UTF8, RecipeJsonData::message,
                RecipeJsonData::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SaveRecipeJson(String recipeId, String json, boolean restore) implements CustomPacketPayload {
        public static final Type<SaveRecipeJson> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
                LimitedRecipeMod.MOD_ID, "save_recipe_json"));
        public static final StreamCodec<ByteBuf, SaveRecipeJson> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SaveRecipeJson::recipeId,
                ByteBufCodecs.STRING_UTF8, SaveRecipeJson::json,
                ByteBufCodecs.BOOL, SaveRecipeJson::restore,
                SaveRecipeJson::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RecipeCatalog(int chunkIndex, int chunkCount, String json) implements CustomPacketPayload {
        public static final Type<RecipeCatalog> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
                LimitedRecipeMod.MOD_ID, "recipe_catalog"));
        public static final StreamCodec<ByteBuf, RecipeCatalog> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                RecipeCatalog::chunkIndex,
                ByteBufCodecs.VAR_INT,
                RecipeCatalog::chunkCount,
                ByteBufCodecs.STRING_UTF8,
                RecipeCatalog::json,
                RecipeCatalog::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record HiddenRecipeList(int chunkIndex, int chunkCount, String json) implements CustomPacketPayload {
        public static final Type<HiddenRecipeList> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
                LimitedRecipeMod.MOD_ID, "hidden_recipe_list"));
        public static final StreamCodec<ByteBuf, HiddenRecipeList> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                HiddenRecipeList::chunkIndex,
                ByteBufCodecs.VAR_INT,
                HiddenRecipeList::chunkCount,
                ByteBufCodecs.STRING_UTF8,
                HiddenRecipeList::json,
                HiddenRecipeList::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SaveRecipeRule(String recipeId, String mode, List<String> playerUuids,
                                 String jeiMode, List<String> jeiPlayerUuids,
                                 boolean hideInJei, boolean disabled)
            implements CustomPacketPayload {
        public static final Type<SaveRecipeRule> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
                LimitedRecipeMod.MOD_ID, "save_recipe_rule"));
        public static final StreamCodec<ByteBuf, SaveRecipeRule> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
                SaveRecipeRule::recipeId,
                ByteBufCodecs.STRING_UTF8,
                SaveRecipeRule::mode,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                SaveRecipeRule::playerUuids,
                ByteBufCodecs.STRING_UTF8,
                SaveRecipeRule::jeiMode,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                SaveRecipeRule::jeiPlayerUuids,
                ByteBufCodecs.BOOL,
                SaveRecipeRule::hideInJei,
                ByteBufCodecs.BOOL,
                SaveRecipeRule::disabled,
                SaveRecipeRule::new
        );

        public SaveRecipeRule {
            playerUuids = List.copyOf(playerUuids);
            jeiPlayerUuids = List.copyOf(jeiPlayerUuids);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record DeleteRecipeRule(String recipeId) implements CustomPacketPayload {
        public static final Type<DeleteRecipeRule> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
                LimitedRecipeMod.MOD_ID, "delete_recipe_rule"));
        public static final StreamCodec<ByteBuf, DeleteRecipeRule> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, DeleteRecipeRule::recipeId, DeleteRecipeRule::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetRecipeRuleEnabled(String recipeId, boolean enabled) implements CustomPacketPayload {
        public static final Type<SetRecipeRuleEnabled> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
                LimitedRecipeMod.MOD_ID, "set_recipe_rule_enabled"));
        public static final StreamCodec<ByteBuf, SetRecipeRuleEnabled> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SetRecipeRuleEnabled::recipeId,
                ByteBufCodecs.BOOL, SetRecipeRuleEnabled::enabled,
                SetRecipeRuleEnabled::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record AccessRulesJsonRequest() implements CustomPacketPayload {
        public static final Type<AccessRulesJsonRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
                LimitedRecipeMod.MOD_ID, "access_rules_json_request"));
        public static final StreamCodec<ByteBuf, AccessRulesJsonRequest> STREAM_CODEC = StreamCodec.unit(
                new AccessRulesJsonRequest());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SaveAccessRulesJson(String json) implements CustomPacketPayload {
        public static final Type<SaveAccessRulesJson> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
                LimitedRecipeMod.MOD_ID, "save_access_rules_json"));
        public static final StreamCodec<ByteBuf, SaveAccessRulesJson> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SaveAccessRulesJson::json, SaveAccessRulesJson::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record AccessRulesJsonData(String json, String message, boolean saved)
            implements CustomPacketPayload {
        public static final Type<AccessRulesJsonData> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
                LimitedRecipeMod.MOD_ID, "access_rules_json_data"));
        public static final StreamCodec<ByteBuf, AccessRulesJsonData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, AccessRulesJsonData::json,
                ByteBufCodecs.STRING_UTF8, AccessRulesJsonData::message,
                ByteBufCodecs.BOOL, AccessRulesJsonData::saved,
                AccessRulesJsonData::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
