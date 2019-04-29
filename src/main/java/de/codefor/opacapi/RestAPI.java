package de.codefor.opacapi;

import de.codefor.opacapi.entity.*;
import de.codefor.opacapi.exception.InvalidSearchTerm;
import de.codefor.opacapi.exception.LibraryNotFound;
import de.codefor.opacapi.exception.SearchFailed;
import de.codefor.opacapi.result.MyResult;
import de.codefor.opacapi.result.MySearchResult;
import de.geeksfactory.opacclient.OpacApiFactory;
import de.geeksfactory.opacclient.apis.OpacApi;
import de.geeksfactory.opacclient.i18n.DummyStringProvider;
import de.geeksfactory.opacclient.networking.HttpClientFactory;
import de.geeksfactory.opacclient.objects.Library;
import de.geeksfactory.opacclient.objects.SearchRequestResult;
import de.geeksfactory.opacclient.searchfields.SearchField;
import de.geeksfactory.opacclient.searchfields.SearchQuery;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.security.Security;
import java.util.*;

@RestController
public class RestAPI {

    public static final String CONFIG_FILES_PATH = "./opacapp-config-files/bibs";

    private static String readFile(String fileName) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(fileName))) {
            StringBuilder sb = new StringBuilder();
            String line = br.readLine();

            while (line != null) {
                sb.append(line);
                sb.append("\n");
                line = br.readLine();
            }
            return sb.toString();
        }
    }


    private File[] getConfigFiles() {
        return new File(CONFIG_FILES_PATH).listFiles();
    }

    private File getConfigFile(String libraryName) {
        return new File(String.format("%s/%s.json", CONFIG_FILES_PATH, libraryName));
    }


    @RequestMapping(value = "/libraries/{libraryName}/search",
            method = RequestMethod.GET,
            produces = {MediaType.APPLICATION_JSON_UTF8_VALUE},
            consumes = MediaType.ALL_VALUE)
    public SearchRequestResult search(@PathVariable String libraryName,
                                      @RequestParam(required = false, defaultValue = "") String isbn,
                                      @RequestParam(required = false, defaultValue = "") String author,
                                      @RequestParam(required = false, defaultValue = "") String title)

            throws IOException, JSONException, OpacApi.OpacErrorException {

        Security.addProvider(new BouncyCastleProvider());

        OpacApi api = getOpacApi(libraryName);

        List<SearchQuery> searchQueries = new ArrayList<>();

        for (SearchField searchField : api.getSearchFields()) {
            if (SearchField.Meaning.ISBN.equals(searchField.getMeaning())) {
                searchQueries.add(new SearchQuery(searchField, isbn));
            }
            if (SearchField.Meaning.AUTHOR.equals(searchField.getMeaning())) {
                searchQueries.add(new SearchQuery(searchField, author));
            }
            if (SearchField.Meaning.TITLE.equals(searchField.getMeaning())) {
                searchQueries.add(new SearchQuery(searchField, title));
            }
        }


        return api.search(searchQueries);
    }

    @RequestMapping(value = "/libraries/{libraryName}/{isbn}",
            method = RequestMethod.GET,
            produces = {MediaType.APPLICATION_JSON_UTF8_VALUE},
            consumes = MediaType.ALL_VALUE)
    public MyResult fetchByISBN(@PathVariable String libraryName,
                                @PathVariable String isbn) {
        new ISBN(isbn).checkValidity();

        Security.addProvider(new BouncyCastleProvider());

        OpacApi api = getOpacApi(libraryName);

        try {
            return new MySearchResult(
                    api.search(SearchQueries.builder(api.getSearchFields())
                            .isbn(isbn).build()));
        } catch (IOException | OpacApi.OpacErrorException | JSONException e) {
            e.printStackTrace();
            throw new SearchFailed();
        }
    }

    @RequestMapping(value = "/libraries/{libraryName}/searchFields",
            method = RequestMethod.GET,
            produces = {MediaType.APPLICATION_JSON_UTF8_VALUE},
            consumes = MediaType.ALL_VALUE)
    public List<MySearchField> searchFields(@PathVariable String libraryName) throws IOException, JSONException, OpacApi.OpacErrorException {

        Security.addProvider(new BouncyCastleProvider());

        OpacApi api = getOpacApi(libraryName);

        List<MySearchField> searchFields = new ArrayList<>();

        for (SearchField searchField : api.getSearchFields()) {
            searchFields.add(MySearchField.from(searchField));
        }

        return searchFields;
    }

    @RequestMapping(value = "/libraries/{libraryName}/catalog/structured-search",
            method = RequestMethod.GET,
            produces = {MediaType.APPLICATION_JSON_UTF8_VALUE},
            consumes = MediaType.ALL_VALUE)
    public CatalogSearchResults structuredSearch(@PathVariable String libraryName,
                                                 HttpServletRequest request)
            throws IOException, JSONException, OpacApi.OpacErrorException {

        Map<String, String[]> map = request.getParameterMap();
        List<SearchQuery> searchQueries = new ArrayList<>();

        for (Map.Entry<String, String[]> entry : map.entrySet()) {
            SearchField searchField = new SearchField() {};
            searchField.setId(entry.getKey());
            searchQueries.add(new SearchQuery(searchField, entry.getValue()[0]));
        }

        OpacApi api = getOpacApi(libraryName);
        Map<String, SearchRequestResult> results = new HashMap<>();
        results.put(libraryName, api.search(searchQueries));

        return new CatalogSearchResults(results);
    }

    @RequestMapping(value = "/libraries/{libraryName}/catalog/search",
            method = RequestMethod.GET,
            produces = {MediaType.APPLICATION_JSON_UTF8_VALUE},
            consumes = MediaType.ALL_VALUE)
    public CatalogSearchResults search(@PathVariable String libraryName,
                                       @RequestParam() String term,
                                       @RequestParam() String searchTerm)
            throws IOException, JSONException, OpacApi.OpacErrorException {

        checkSearchTerm(searchTerm);

        Security.addProvider(new BouncyCastleProvider());

        Map<String, SearchRequestResult> results = new HashMap<>();

        OpacApi api = getOpacApi(libraryName);

        List<SearchQuery> searchQueries = new ArrayList<>();

        if (searchTerm.equalsIgnoreCase("Title")) {
            for (SearchField searchField : api.getSearchFields()) {
                if (SearchField.Meaning.TITLE.equals(searchField.getMeaning())) {
                    searchQueries.add(new SearchQuery(searchField, term));
                }
            }
        } else if (searchTerm.equalsIgnoreCase("ISBN") | searchTerm.equalsIgnoreCase("ISBN13")) {

            for (SearchField searchField : api.getSearchFields()) {
                if (SearchField.Meaning.ISBN.equals(searchField.getMeaning())) {
                    searchQueries.add(new SearchQuery(searchField, term));
                }
            }
        }

        results.put(libraryName, api.search(searchQueries));

        return new CatalogSearchResults(results);
    }


    @RequestMapping(value = "/libraries/{libraryName}/record/{id}",
            method = RequestMethod.GET,
            produces = {MediaType.APPLICATION_JSON_UTF8_VALUE},
            consumes = MediaType.ALL_VALUE)
    public RecordResult search(@PathVariable String libraryName,
                               @PathVariable() String id)
            throws IOException, OpacApi.OpacErrorException {

        Security.addProvider(new BouncyCastleProvider());

        OpacApi api = getOpacApi(libraryName);

        return new RecordResult(api.getResultById(id, null));
    }


    // GET /record/branches/<id>
    @RequestMapping(value = "/libraries/{libraryName}/record/branches/{id}",
            method = RequestMethod.GET,
            produces = {MediaType.APPLICATION_JSON_UTF8_VALUE},
            consumes = MediaType.ALL_VALUE)
    public RecordAvailability getAvailibility(@PathVariable String libraryName,
                                              @PathVariable() String id)
            throws IOException, OpacApi.OpacErrorException {

        Security.addProvider(new BouncyCastleProvider());

        OpacApi api = getOpacApi(libraryName);

        return new RecordAvailability(api.getResultById(id, null));
    }

    private void checkSearchTerm(String searchTerm) {
        if (!searchTerm.equals("ISBN") &&
                !searchTerm.equals("ISBN13") &&
                !searchTerm.equals("Title"))
            throw new InvalidSearchTerm(searchTerm);
    }

    private OpacApi getOpacApi(String libraryName) {
        try {
            File file = getConfigFile(libraryName);
            Library library = Library.fromJSON(libraryName, new JSONObject(readFile(file.getAbsolutePath())));
            return OpacApiFactory.create(library, new DummyStringProvider(),
                    new HttpClientFactory("HelloOpac/1.0.0", new OpacAPI().pathToTrustStore()), null, null);

        } catch (JSONException | IOException e) {
            e.printStackTrace();
            throw new LibraryNotFound(libraryName);
        }
    }

    private List<String> libraries(String nameOfCity) {
        List<String> libraries = new ArrayList<>();

        for (File file : getConfigFiles()) {

            String libraryName = file.getName().replace(".json", "");

            libraries.add(libraryName);
        }

        return libraries;
    }


    @RequestMapping(value = "/libraries",
            method = RequestMethod.GET,
            produces = {MediaType.APPLICATION_JSON_UTF8_VALUE},
            consumes = MediaType.ALL_VALUE)
    public List<String> libraries() {

        Security.addProvider(new BouncyCastleProvider());

        List<String> libraries = new ArrayList<>();

        for (File file : getConfigFiles()) {
            libraries.add(file.getName().replace(".json", ""));
        }

        return libraries;
    }


}
