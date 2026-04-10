using Boomerang.Sample.Web;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);

builder.Services.Configure<BoomerangOptions>(
    builder.Configuration.GetSection(BoomerangOptions.SectionName));

builder.Services.AddSingleton<BoomerangDemoServices>(sp =>
{
    var o = sp.GetRequiredService<IOptions<BoomerangOptions>>().Value;
    return BoomerangDemoServices.Create(o);
});

builder.Services.AddSingleton<LastWebhookStore>();

builder.Services.AddControllers();

var app = builder.Build();

app.UseStaticFiles();
app.MapControllers();
app.MapGet("/", () => Results.Redirect("/index.html", permanent: false));

app.Run();
